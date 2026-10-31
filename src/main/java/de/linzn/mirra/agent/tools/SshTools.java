/*
 * Copyright (c) 2026 MirraNET, Niklas Linz. All rights reserved.
 *
 * This file is part of the MirraNET project and is licensed under the
 * GNU Lesser General Public License v3.0 (LGPLv3).
 *
 * You may use, distribute and modify this code under the terms
 * of the LGPLv3 license. You should have received a copy of the
 * license along with this file. If not, see <https://www.gnu.org/licenses/lgpl-3.0.html>
 * or contact: niklas.linz@mirranet.de
 */

package de.linzn.mirra.agent.tools;

import de.linzn.mirra.MirraConfig;
import de.linzn.mirra.MirraPlugin;
import de.linzn.mirra.agent.ChatContext;
import de.linzn.mirra.agent.MirraAgent;
import de.linzn.mirra.identitySystem.OwnerActions;
import de.linzn.mirra.ssh.ProcessRunner;
import de.linzn.mirra.ssh.SshKeyStore;
import de.linzn.mirra.ssh.SshLevel;
import de.linzn.mirra.ssh.SshServer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static de.linzn.mirra.agent.tools.Schema.*;
import static de.linzn.mirra.identitySystem.AiPermissions.SHELL;

/**
 * Shell commands and the SSH servers of Mirra's own key store. Everything needs {@code SHELL}; trusting a host key,
 * installing the server side and changing the level need the confirmation of an owner.
 */
public final class SshTools {

    private static final List<String> LEVELS = List.of("read", "ops", "full");

    private SshTools() {
    }

    /**
     * @param store Mirra's own key store, or null (agent.shell.keyStore off): then only {@code shell_run} with SSH
     *              through the STEM user's own keys, as before the key store
     */
    public static List<AgentTool> create(MirraPlugin plugin, MirraConfig config, SshKeyStore store) {
        List<AgentTool> tools = new ArrayList<>();
        if (store == null) {
            if (!config.shellKeyStore) {
                tools.add(legacyShell(plugin, config));
            } else {
                /* key store wanted but broken: local commands only, never fall back to the STEM user's keys */
                tools.add(localShell(plugin, config));
            }
            return tools;
        }

        tools.add(new SimpleTool("shell_run",
                "Run a shell command on the STEM server, or with 'server' on one of the registered SSH servers "
                        + "(ssh_servers; Mirra's own key per server, level read/ops/full is enforced by the server). "
                        + "Returns exit code and output. On level read/ops use sudo for the allowed root commands "
                        + "(systemctl, docker, apt-get). Ask before commands that change or delete something, restart "
                        + "services or machines, unless the person clearly asked for exactly that.",
                Schema.object()
                        .prop("command", string("shell command, e.g. 'df -h' or 'sudo systemctl restart nginx'"))
                        .prop("server", nullable(string("name of a registered SSH server; null to run on the STEM server")))
                        .prop("timeout_seconds", nullable(integer("timeout, default from the configuration")))
                        .build(),
                chat -> config.shellEnabled && chat.has(SHELL), (chat, args) -> {
            String command = requireString(args, "command");
            String serverName = optString(args, "server");
            Integer timeoutArg = optInt(args, "timeout_seconds");
            int timeout = timeoutArg == null ? config.shellTimeoutSeconds : Math.max(1, Math.min(3600, timeoutArg));
            boolean automatic = MirraAgent.currentTurn().automatic();
            if (serverName == null || serverName.isBlank()) {
                plugin.getPluginLogger().info("Shell by " + chat.name() + ": " + command);
                return ProcessRunner.run(List.of("/bin/sh", "-c", command), null, timeout);
            }
            SshServer server = store.get(serverName);
            if (!server.trusted()) {
                throw new IllegalStateException("The host key of " + server.name() + " is not confirmed yet");
            }
            if (automatic && server.level().compareTo(config.shellAutomaticMaxLevel) > 0) {
                throw new IllegalStateException("Automatic runs (watchdog, tasks) may only use servers up to level "
                        + config.shellAutomaticMaxLevel.id() + "; " + server.name() + " has " + server.level().id()
                        + ". Report the problem to the person instead.");
            }
            if (command.trim().startsWith("__mirra_")) {
                throw new IllegalArgumentException("Internal command, use ssh_server_manage");
            }
            plugin.getPluginLogger().info("Shell by " + chat.name() + " on " + server.name() + ": " + command);
            JSONObject result = store.run(server, command, null, timeout);
            store.audit(chat.name(), server, command, result, automatic);
            return result.put("server", server.name()).put("level", server.level().id());
        }));

        tools.add(new SimpleTool("ssh_servers",
                "The SSH servers Mirra can log in to with her own keys: name, address, login user, level, whether the "
                        + "host key is confirmed, key age.",
                Schema.empty(), chat -> config.shellEnabled && chat.has(SHELL), (chat, args) -> {
            JSONArray list = new JSONArray();
            for (SshServer server : store.list()) {
                list.put(describe(server, config));
            }
            return new JSONObject().put("servers", list).put("automatic_max_level", config.shellAutomaticMaxLevel.id());
        }));

        tools.add(new SimpleTool("ssh_server_add",
                "Register a server for Mirra's SSH access: generates a key pair only for this server and reads the host "
                        + "keys. An owner has to confirm the host key fingerprint (compared on the server), then the key "
                        + "is installed on the server: with 'install_as' automatically through the STEM user's existing "
                        + "SSH login (as root or a sudo user), otherwise by running the returned installer command on "
                        + "the server. Levels: read (look only), ops (also restart services/containers, apt upgrade, "
                        + "reboot), full (sudo for everything).",
                Schema.object()
                        .prop("name", string("short name, e.g. 'nas' or 'mail'"))
                        .prop("host", string("host name or IP address"))
                        .prop("port", nullable(integer("SSH port, default 22")))
                        .prop("level", enumeration("what Mirra may do on the server", LEVELS))
                        .prop("login_user", nullable(string("account Mirra logs in with, default 'mirra' (created by the installer)")))
                        .prop("allowed_from", nullable(string("addresses/networks Mirra's key is accepted from, as the server "
                                + "sees STEM, e.g. '10.50.0.11'; null for any (less safe)")))
                        .prop("install_as", nullable(string("install automatically through the STEM user's login as this "
                                + "user (root or a sudo user); null to install by hand")))
                        .prop("install_port", nullable(integer("SSH port for install_as, default the server's port")))
                        .build(),
                chat -> config.shellEnabled && chat.has(SHELL), (chat, args) -> {
            int port = optInt(args, "port") == null ? 22 : optInt(args, "port");
            String user = optString(args, "login_user") == null ? "mirra" : optString(args, "login_user").trim();
            SshServer server = store.add(requireString(args, "name"), requireString(args, "host").trim(), port, user,
                    SshLevel.parse(requireString(args, "level")), optString(args, "allowed_from"));
            String installAs = optString(args, "install_as");
            Integer installPort = optInt(args, "install_port");
            List<String> confirmed = server.hostKeys();
            String description = "SSH-Zugang '" + server.name() + "' (" + server.user() + "@" + server.host() + ":" + server.port()
                    + ", Stufe " + server.level().id() + ") freigeben. Host-Key: " + String.join(", ", SshKeyStore.fingerprints(server))
                    + " - vorher auf dem Server vergleichen mit: ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub"
                    + (installAs == null ? "" : ". Danach als " + installAs + " einloggen und Mirras Login einrichten");
            OwnerActions.Pending pending = plugin.getOwnerActions().request(chat, description, () -> {
                store.trust(server.name(), confirmed);
                if (installAs == null) {
                    return "Host key confirmed for " + server.name() + ". Install by hand: " + store.installCommand(server.name());
                }
                return installResult(store, server.name(), installAs, installPort == null ? server.port() : installPort);
            });
            JSONObject result = new JSONObject().put("server", describe(server, config))
                    .put("public_key", store.publicKey(server.name()))
                    .put("pending_action_id", pending.id()).put("description", pending.description());
            if (installAs == null) {
                result.put("install_by_hand", "After the confirmation: copy " + store.installerFile() + " to the server "
                        + "(send_file with that path) and run there: " + store.installCommand(server.name()));
            }
            result.put("next", OwnerActions.isOwner(chat.identityUser())
                    ? "Nothing is usable yet. Show the owner the fingerprint and ask to compare it on the server; after a "
                    + "yes in the next message call confirm_owner_action with this id."
                    : "Nothing is usable yet. The owner was asked to confirm the fingerprint.");
            return result;
        }));

        tools.add(new SimpleTool("ssh_server_manage",
                "Manage a registered SSH server. check: log in and read the installed level; disable/enable: stop or allow "
                        + "using it; rotate: replace the key pair (new key sent through the old login); remove: delete "
                        + "the key on the server and in Mirra; host_key: read the host key again after the server was "
                        + "reinstalled (owner confirms again); install: install or update the server side through the "
                        + "STEM user's login with 'install_as', optionally with a new 'level' (owner confirms); "
                        + "installer: the command to install by hand.",
                Schema.object()
                        .prop("name", string("name of the server"))
                        .prop("action", enumeration("what to do", List.of("check", "disable", "enable", "rotate", "remove",
                                "host_key", "install", "installer")))
                        .prop("level", nullable(enumeration("new level for action install", LEVELS)))
                        .prop("install_as", nullable(string("for action install: user of the STEM user's login, e.g. root")))
                        .prop("install_port", nullable(integer("for action install: SSH port, default the server's port")))
                        .build(),
                chat -> config.shellEnabled && chat.has(SHELL), (chat, args) -> {
            SshServer server = store.get(requireString(args, "name"));
            String action = requireString(args, "action");
            plugin.getPluginLogger().info("SSH " + action + " of " + server.name() + " by " + chat.name());
            switch (action) {
                case "check" -> {
                    Map<String, String> info = store.info(server.name());
                    return new JSONObject().put("installed", new JSONObject(info)).put("server", describe(store.get(server.name()), config));
                }
                case "disable", "enable" -> {
                    return describe(store.setEnabled(server.name(), action.equals("enable")), config);
                }
                case "rotate" -> {
                    return new JSONObject().put("result", store.rotate(server.name())).put("server", describe(store.get(server.name()), config));
                }
                case "remove" -> {
                    String revoked = server.trusted() && server.enabled() ? store.revoke(server.name()) : "Not logged in to remove the key on the server";
                    store.remove(server.name());
                    return new JSONObject().put("removed", server.name()).put("on_server", revoked);
                }
                case "host_key" -> {
                    SshServer updated = store.rescanHostKeys(server.name());
                    List<String> confirmed = updated.hostKeys();
                    OwnerActions.Pending pending = plugin.getOwnerActions().request(chat, "Neuen Host-Key von SSH-Server '"
                            + updated.name() + "' (" + updated.host() + ":" + updated.port() + ") bestätigen: "
                            + String.join(", ", SshKeyStore.fingerprints(updated)), () -> {
                        store.trust(updated.name(), confirmed);
                        return "Host key confirmed for " + updated.name();
                    });
                    return pendingResult(pending, chat).put("server", describe(updated, config));
                }
                case "install" -> {
                    String installAs = optString(args, "install_as");
                    if (installAs == null) {
                        throw new IllegalArgumentException("install_as missing (e.g. root); or use action installer to install by hand");
                    }
                    String levelArg = optString(args, "level");
                    SshLevel level = levelArg == null ? server.level() : SshLevel.parse(levelArg);
                    Integer installPort = optInt(args, "install_port");
                    OwnerActions.Pending pending = plugin.getOwnerActions().request(chat, "Mirras SSH-Login auf '" + server.name()
                            + "' als " + installAs + " einrichten/aktualisieren, Stufe " + level.id(), () -> {
                        store.setLevel(server.name(), level);
                        return installResult(store, server.name(), installAs, installPort == null ? server.port() : installPort);
                    });
                    return pendingResult(pending, chat);
                }
                case "installer" -> {
                    return new JSONObject().put("installer_file", store.installerFile().toString())
                            .put("command", store.installCommand(server.name()))
                            .put("note", "Send the installer file (send_file with the path), run the command on the server as root, then action check.");
                }
                default -> throw new IllegalArgumentException("Unknown action " + action);
            }
        }));
        return tools;
    }

    /**
     * shell_run as before the key store: SSH with the keys and config of the STEM user to any host (or the hosts in
     * agent.shell.allowedHosts).
     */
    private static AgentTool legacyShell(MirraPlugin plugin, MirraConfig config) {
        return new SimpleTool("shell_run",
                "Run a shell command on the STEM server, or with 'host' via SSH on another machine (key based login of the "
                        + "STEM user, no password prompts). Returns exit code and output. Ask before commands that change or "
                        + "delete something, restart services or machines, unless the person clearly asked for exactly that.",
                Schema.object()
                        .prop("command", string("shell command, e.g. 'df -h' or 'systemctl status nginx'"))
                        .prop("host", nullable(string("SSH host name or IP; null to run on the STEM server")))
                        .prop("user", nullable(string("SSH user; null for the configured default")))
                        .prop("port", nullable(integer("SSH port; null for the configured default")))
                        .prop("timeout_seconds", nullable(integer("timeout, default from the configuration")))
                        .build(),
                chat -> config.shellEnabled && chat.has(SHELL), (chat, args) -> {
            String command = requireString(args, "command");
            String host = optString(args, "host");
            Integer timeoutArg = optInt(args, "timeout_seconds");
            int timeout = timeoutArg == null ? config.shellTimeoutSeconds : Math.max(1, Math.min(3600, timeoutArg));
            List<String> process = new ArrayList<>();
            if (host != null) {
                String user = optString(args, "user") == null ? config.shellSshUser : optString(args, "user");
                Integer portArg = optInt(args, "port");
                int port = portArg == null ? config.shellSshPort : portArg;
                if (!safeArgument(host) || !safeArgument(user)) {
                    throw new IllegalArgumentException("Invalid host or user");
                }
                if (!config.shellAllowedHosts.isEmpty() && !config.shellAllowedHosts.contains(host.toLowerCase(java.util.Locale.ROOT))) {
                    throw new IllegalArgumentException("Host not allowed, allowed are " + config.shellAllowedHosts);
                }
                process.addAll(List.of("ssh", "-p", String.valueOf(port), "-o", "BatchMode=yes", "-o", "ConnectTimeout=10",
                        user + "@" + host, command));
            } else {
                process.addAll(List.of("/bin/sh", "-c", command));
            }
            plugin.getPluginLogger().info("Shell by " + chat.name() + (host == null ? "" : " on " + host) + ": " + command);
            return ProcessRunner.run(process, null, timeout);
        });
    }

    private static AgentTool localShell(MirraPlugin plugin, MirraConfig config) {
        return new SimpleTool("shell_run",
                "Run a shell command on the STEM server. Returns exit code and output. SSH to other machines is not "
                        + "available right now. Ask before commands that change or delete something or restart services.",
                Schema.object()
                        .prop("command", string("shell command, e.g. 'df -h'"))
                        .prop("timeout_seconds", nullable(integer("timeout, default from the configuration")))
                        .build(),
                chat -> config.shellEnabled && chat.has(SHELL), (chat, args) -> {
            String command = requireString(args, "command");
            Integer timeoutArg = optInt(args, "timeout_seconds");
            int timeout = timeoutArg == null ? config.shellTimeoutSeconds : Math.max(1, Math.min(3600, timeoutArg));
            plugin.getPluginLogger().info("Shell by " + chat.name() + ": " + command);
            return ProcessRunner.run(List.of("/bin/sh", "-c", command), null, timeout);
        });
    }

    private static boolean safeArgument(String value) {
        return value != null && !value.isBlank() && !value.startsWith("-") && value.chars().noneMatch(c -> Character.isWhitespace(c) || c == '@');
    }

    private static String installResult(SshKeyStore store, String name, String installAs, int port) throws Exception {
        JSONObject result = store.install(name, installAs, port);
        String output = ProcessRunner.text(result);
        if (!ProcessRunner.ok(result)) {
            return "Installation on " + name + " failed: " + output;
        }
        Map<String, String> info = store.info(name);
        return "Installed on " + name + ": login " + info.get("LOGIN_USER") + ", level " + info.get("LEVEL") + ", from " + info.get("FROM");
    }

    private static JSONObject describe(SshServer server, MirraConfig config) {
        JSONObject json = new JSONObject().put("name", server.name()).put("host", server.host()).put("port", server.port())
                .put("user", server.user()).put("level", server.level().id()).put("allowed_from", server.from())
                .put("host_key_confirmed", server.trusted()).put("enabled", server.enabled())
                .put("host_key_fingerprints", new JSONArray(SshKeyStore.fingerprints(server)))
                .put("key_age_days", Duration.between(server.keyCreated(), Instant.now()).toDays())
                .put("last_used", server.lastUsed() == null ? JSONObject.NULL : server.lastUsed().toString());
        if (server.level().compareTo(config.shellAutomaticMaxLevel) > 0) {
            json.put("automatic_runs", "not allowed (level above " + config.shellAutomaticMaxLevel.id() + ")");
        }
        return json;
    }

    private static JSONObject pendingResult(OwnerActions.Pending pending, ChatContext chat) {
        JSONObject result = new JSONObject().put("pending_action_id", pending.id()).put("description", pending.description());
        result.put("next", OwnerActions.isOwner(chat.identityUser())
                ? "Nothing changed yet. Ask the owner to confirm exactly this; after a yes in the next message call confirm_owner_action with this id."
                : "Nothing changed yet. The owner was asked to confirm; tell the person that the owner decides.");
        return result;
    }
}
