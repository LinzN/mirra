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

package de.linzn.mirra.ssh;

import de.linzn.stem.api.StemLogger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Mirra's own SSH key store, separate from the keys of the STEM user: one ed25519 key pair and one pinned
 * known_hosts file per server. Layout below the plugin folder (directories 700, files 600):
 * <pre>
 * ssh/servers.json          registered servers
 * ssh/keys/&lt;name&gt;           private key, &lt;name&gt;.pub public key
 * ssh/known_hosts/&lt;name&gt;    host keys of the server, written when an owner confirmed the fingerprints
 * ssh/audit.log             one JSON line per command
 * ssh/mirra-ssh-install.sh  installer for the server side
 * </pre>
 * Mirra's connections never read ~/.ssh of the STEM user ({@code -F /dev/null}, only the server's own key and
 * known_hosts). Only {@link #install} logs in with the STEM user's own login, to set up the server once, and even
 * then the host key must match the confirmed one.
 */
public final class SshKeyStore {

    public static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,47}");
    private static final Pattern USER = Pattern.compile("[a-z_][a-z0-9_-]{0,31}");
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.:-]{0,252}");
    private static final Pattern FROM = Pattern.compile("-|[0-9A-Fa-f.:/,*?]{1,500}");
    private static final Pattern KEY_DATA = Pattern.compile("[A-Za-z0-9+/]+=*");
    private static final String INSTALLER = "mirra-ssh-install.sh";

    private final Path folder;
    private final Path keys;
    private final Path knownHosts;
    private final Path serversFile;
    private final Path auditFile;
    private final StemLogger logger;
    private final Map<String, SshServer> servers = new TreeMap<>();

    public SshKeyStore(Path folder, StemLogger logger) throws IOException {
        this.folder = folder;
        this.keys = folder.resolve("keys");
        this.knownHosts = folder.resolve("known_hosts");
        this.serversFile = folder.resolve("servers.json");
        this.auditFile = folder.resolve("audit.log");
        this.logger = logger;
        for (Path dir : List.of(folder, this.keys, this.knownHosts)) {
            Files.createDirectories(dir);
            restrict(dir, "rwx------");
        }
        if (Files.exists(this.serversFile)) {
            JSONArray array = new JSONArray(Files.readString(this.serversFile));
            for (int i = 0; i < array.length(); i++) {
                SshServer server = SshServer.fromJson(array.getJSONObject(i));
                this.servers.put(server.name(), server);
            }
        }
    }

    public Path folder() {
        return this.folder;
    }

    /* ------------------------------------------------------------------ servers */

    public synchronized List<SshServer> list() {
        return new ArrayList<>(this.servers.values());
    }

    public synchronized Optional<SshServer> find(String name) {
        return Optional.ofNullable(name == null ? null : this.servers.get(name.trim().toLowerCase(Locale.ROOT)));
    }

    public SshServer get(String name) {
        return this.find(name).orElseThrow(() -> new IllegalArgumentException("Unknown SSH server '" + name
                + "', known: " + this.list().stream().map(SshServer::name).toList()));
    }

    /**
     * Register a server: read its host keys and generate its key pair. The server is usable once an owner
     * confirmed the host key fingerprints ({@link #trust}) and the public key is installed on it.
     */
    public synchronized SshServer add(String name, String host, int port, String user, SshLevel level, String from) throws Exception {
        name = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        from = from == null || from.isBlank() ? "-" : from.replace(" ", "");
        check(NAME, name, "name (a-z, 0-9, . _ -)");
        check(HOST, host, "host");
        check(USER, user, "login user");
        check(FROM, from, "allowed_from (addresses or networks separated by commas)");
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid port " + port);
        }
        if (user.equals("root") && level != SshLevel.FULL) {
            throw new IllegalArgumentException("A root login always has level full; use a separate login user (e.g. mirra) for read or ops");
        }
        if (this.servers.containsKey(name)) {
            throw new IllegalArgumentException("SSH server '" + name + "' exists already");
        }
        List<String> hostKeys = scanHostKeys(host, port);
        this.generateKey(name);
        Instant now = Instant.now();
        SshServer server = new SshServer(name, host, port, user, level, from, hostKeys, false, true, now, now, null);
        this.servers.put(name, server);
        this.save();
        this.logger.info("SSH server " + name + " added (" + user + "@" + host + ":" + port + ", " + level.id() + ")");
        return server;
    }

    /**
     * Pin the host keys after an owner compared the fingerprints.
     *
     * @param confirmed the host keys the owner was shown; must still be the current ones
     */
    public synchronized SshServer trust(String name, List<String> confirmed) throws IOException {
        SshServer server = this.get(name);
        if (!server.hostKeys().equals(confirmed)) {
            throw new IllegalStateException("The host keys of " + name + " changed since the confirmation was requested");
        }
        StringBuilder lines = new StringBuilder();
        for (String key : server.hostKeys()) {
            lines.append(server.name()).append(' ').append(key).append('\n');
        }
        writePrivate(this.knownHosts.resolve(server.name()), lines.toString());
        return this.put(server.withTrusted(true));
    }

    /**
     * Read the host keys again (e.g. after the server was reinstalled); the server is untrusted until an owner
     * confirms the new fingerprints.
     */
    public synchronized SshServer rescanHostKeys(String name) throws Exception {
        SshServer server = this.get(name);
        List<String> hostKeys = scanHostKeys(server.host(), server.port());
        Files.deleteIfExists(this.knownHosts.resolve(server.name()));
        return this.put(server.withHostKeys(hostKeys));
    }

    public synchronized SshServer setEnabled(String name, boolean enabled) throws IOException {
        return this.put(this.get(name).withEnabled(enabled));
    }

    public synchronized SshServer setLevel(String name, SshLevel level) throws IOException {
        SshServer server = this.get(name);
        if (server.user().equals("root") && level != SshLevel.FULL) {
            throw new IllegalArgumentException("A root login always has level full");
        }
        return this.put(server.withLevel(level));
    }

    /**
     * Remove the server and delete its key pair and host keys. Call {@link #revoke} before to remove the key on
     * the server too.
     */
    public synchronized void remove(String name) throws IOException {
        SshServer server = this.get(name);
        this.servers.remove(server.name());
        this.save();
        for (String file : List.of(server.name(), server.name() + ".pub", server.name() + ".new", server.name() + ".new.pub")) {
            Files.deleteIfExists(this.keys.resolve(file));
        }
        Files.deleteIfExists(this.knownHosts.resolve(server.name()));
        this.logger.info("SSH server " + server.name() + " removed, key deleted");
    }

    /* ------------------------------------------------------------------ keys */

    /**
     * @return the public key line ("ssh-ed25519 AAAA... mirra-key:name")
     */
    public String publicKey(String name) throws IOException {
        return Files.readString(this.keys.resolve(this.get(name).name() + ".pub")).trim();
    }

    /**
     * @return fingerprints of the server's host keys, e.g. "SHA256:abc... (ssh-ed25519)"
     */
    public static List<String> fingerprints(SshServer server) {
        return server.hostKeys().stream().map(SshKeyStore::fingerprint).toList();
    }

    static String fingerprint(String hostKey) {
        String[] parts = hostKey.split(" ");
        try {
            byte[] blob = Base64.getDecoder().decode(parts[1]);
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(blob);
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(hash) + " (" + parts[0] + ")";
        } catch (Exception e) {
            return "invalid key (" + parts[0] + ")";
        }
    }

    private void generateKey(String name) throws Exception {
        this.generateKeyFile(name, this.keys.resolve(name));
    }

    private void generateKeyFile(String name, Path file) throws Exception {
        Files.deleteIfExists(file);
        Files.deleteIfExists(Path.of(file + ".pub"));
        JSONObject result = ProcessRunner.run(List.of("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-C", "mirra-key:" + name,
                "-f", file.toString()), null, 30);
        if (!ProcessRunner.ok(result) || !Files.exists(file)) {
            throw new IllegalStateException("ssh-keygen failed: " + ProcessRunner.text(result));
        }
        restrict(file, "rw-------");
    }

    private static List<String> scanHostKeys(String host, int port) throws Exception {
        JSONObject result = ProcessRunner.run(List.of("ssh-keyscan", "-T", "10", "-p", String.valueOf(port),
                "-t", "ed25519,ecdsa,rsa", "--", host), null, 40);
        Set<String> keys = new LinkedHashSet<>();
        for (String line : ProcessRunner.text(result).split("\n")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 3 && !parts[0].startsWith("#") && (parts[1].startsWith("ssh-") || parts[1].startsWith("ecdsa-"))
                    && KEY_DATA.matcher(parts[2]).matches()) {
                keys.add(parts[1] + " " + parts[2]);
            }
        }
        if (keys.isEmpty()) {
            throw new IllegalStateException("No SSH host key from " + host + ":" + port + " (not reachable or no SSH server): "
                    + ProcessRunner.text(result));
        }
        return new ArrayList<>(keys);
    }

    /* ------------------------------------------------------------------ connections */

    /**
     * ssh command line for a server: only its own key and pinned host keys, no config, agent or forwarding.
     */
    public List<String> sshCommand(SshServer server, String remoteCommand) {
        return this.sshCommand(server, remoteCommand, this.keys.resolve(server.name()));
    }

    private List<String> sshCommand(SshServer server, String remoteCommand, Path key) {
        List<String> command = new ArrayList<>(List.of("ssh", "-F", "/dev/null", "-i", key.toString(),
                "-p", String.valueOf(server.port()), "-l", server.user()));
        command.addAll(this.hostKeyOptions(server));
        command.addAll(List.of("-o", "IdentitiesOnly=yes", "-o", "IdentityAgent=none", "-o", "PasswordAuthentication=no",
                "-o", "KbdInteractiveAuthentication=no", "-o", "ForwardAgent=no", "-o", "ForwardX11=no",
                "-o", "ClearAllForwardings=yes", "-o", "ServerAliveInterval=15", "-o", "ServerAliveCountMax=3",
                "--", server.host(), remoteCommand));
        return command;
    }

    private List<String> hostKeyOptions(SshServer server) {
        if (!server.trusted()) {
            throw new IllegalStateException("The host key of " + server.name() + " is not confirmed by an owner yet");
        }
        return List.of("-o", "BatchMode=yes", "-o", "ConnectTimeout=10", "-o", "StrictHostKeyChecking=yes",
                "-o", "UserKnownHostsFile=" + this.knownHosts.resolve(server.name()), "-o", "GlobalKnownHostsFile=/dev/null",
                "-o", "HostKeyAlias=" + server.name(), "-o", "CheckHostIP=no", "-o", "UpdateHostKeys=no");
    }

    /**
     * Run a command on the server with Mirra's key.
     */
    public JSONObject run(SshServer server, String command, String stdin, int timeoutSeconds) throws Exception {
        if (!server.enabled()) {
            throw new IllegalStateException("SSH server " + server.name() + " is disabled");
        }
        JSONObject result = ProcessRunner.run(this.sshCommand(server, command), stdin, timeoutSeconds);
        synchronized (this) {
            this.find(server.name()).ifPresent(current -> {
                try {
                    this.put(current.withLastUsed(Instant.now()));
                } catch (IOException e) {
                    this.logger.warning("Could not save SSH servers: " + e.getMessage());
                }
            });
        }
        return result;
    }

    /**
     * Install or update the server side ({@code mirra-ssh-install.sh}) by logging in with the STEM user's own
     * SSH login (keys and config of the STEM user), checked against the confirmed host key.
     *
     * @param adminUser login with root rights: root, or a user with password-less sudo
     */
    public JSONObject install(String name, String adminUser, int adminPort) throws Exception {
        SshServer server = this.get(name);
        check(USER, adminUser, "admin user");
        String[] key = this.publicKey(server.name()).split(" ");
        String arguments = String.join(" ", quote(server.user()), quote(server.level().id()), quote(server.name()),
                quote(server.from()), quote(key[0]), quote(key[1]));
        String remote = (adminUser.equals("root") ? "" : "sudo -n ") + "bash -s -- " + arguments;
        List<String> command = new ArrayList<>(List.of("ssh", "-p", String.valueOf(adminPort), "-l", adminUser));
        command.addAll(this.hostKeyOptions(server));
        command.addAll(List.of("--", server.host(), remote));
        this.logger.info("Installing Mirra's SSH login on " + server.name() + " as " + adminUser);
        return ProcessRunner.run(command, installer(), 180);
    }

    /**
     * @return contents of /etc/mirra/login.conf of the server (LOGIN_USER, LEVEL, NAME, FROM); the stored level is
     * updated to the one installed on the server
     */
    public Map<String, String> info(String name) throws Exception {
        SshServer server = this.get(name);
        JSONObject result = this.run(server, "__mirra_info__", null, 30);
        if (!ProcessRunner.ok(result)) {
            throw new IllegalStateException("Login on " + server.name() + " failed: " + ProcessRunner.text(result));
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : ProcessRunner.text(result).split("\n")) {
            int index = line.indexOf('=');
            if (index > 0) {
                values.put(line.substring(0, index).trim(), line.substring(index + 1).trim());
            }
        }
        if (!server.name().equals(values.get("NAME"))) {
            throw new IllegalStateException("The server answered as '" + values.get("NAME") + "', expected '" + server.name() + "'");
        }
        SshLevel installed = SshLevel.parse(values.get("LEVEL"));
        synchronized (this) {
            this.put(this.get(name).withLevel(installed));
        }
        return values;
    }

    /**
     * Replace the key pair: the new public key is sent through the old login, then the new one is tested.
     */
    public synchronized String rotate(String name) throws Exception {
        SshServer server = this.get(name);
        Path current = this.keys.resolve(server.name());
        Path next = this.keys.resolve(server.name() + ".new");
        this.generateKeyFile(server.name(), next);
        String publicKey = Files.readString(Path.of(next + ".pub")).trim();
        JSONObject result = this.run(server, "__mirra_rotate__", publicKey + "\n", 60);
        if (!ProcessRunner.ok(result) || !ProcessRunner.text(result).contains("rotated")) {
            Files.deleteIfExists(next);
            Files.deleteIfExists(Path.of(next + ".pub"));
            throw new IllegalStateException("The server did not accept the new key, the old one stays: " + ProcessRunner.text(result));
        }
        /* the server only knows the new key now */
        Files.move(next, current, StandardCopyOption.REPLACE_EXISTING);
        Files.move(Path.of(next + ".pub"), Path.of(current + ".pub"), StandardCopyOption.REPLACE_EXISTING);
        this.put(this.get(name).withKeyCreated(Instant.now()));
        JSONObject test = this.run(this.get(name), "__mirra_info__", null, 30);
        if (!ProcessRunner.ok(test)) {
            throw new IllegalStateException("Key replaced on the server, but the login with the new key failed: " + ProcessRunner.text(test));
        }
        this.logger.info("SSH key of " + server.name() + " rotated");
        return "New key active on " + server.name();
    }

    /**
     * Remove Mirra's key from authorized_keys of the server (best effort).
     */
    public String revoke(String name) {
        SshServer server = this.get(name);
        try {
            JSONObject result = this.run(server, "__mirra_revoke__", null, 30);
            return ProcessRunner.ok(result) ? "Key removed on the server" : "Could not remove the key on the server: " + ProcessRunner.text(result);
        } catch (Exception e) {
            return "Could not remove the key on the server: " + e.getMessage();
        }
    }

    /**
     * Write the installer next to the keys.
     *
     * @return the command line to run it on the server as root
     */
    public String installCommand(String name) throws IOException {
        SshServer server = this.get(name);
        Path script = this.folder.resolve(INSTALLER);
        writePrivate(script, installer());
        String[] key = this.publicKey(server.name()).split(" ");
        return "sudo bash " + INSTALLER + " " + String.join(" ", quote(server.user()), quote(server.level().id()), quote(server.name()),
                quote(server.from()), quote(key[0]), quote(key[1]));
    }

    public Path installerFile() {
        return this.folder.resolve(INSTALLER);
    }

    /* ------------------------------------------------------------------ audit */

    public void audit(String person, SshServer server, String command, JSONObject result, boolean automatic) {
        JSONObject entry = new JSONObject().put("time", Instant.now().toString()).put("person", person)
                .put("server", server.name()).put("level", server.level().id()).put("automatic", automatic)
                .put("command", command).put("exit_code", result.has("exit_code") ? result.get("exit_code") : JSONObject.NULL);
        synchronized (this.auditFile) {
            try {
                boolean created = !Files.exists(this.auditFile);
                Files.writeString(this.auditFile, entry + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                if (created) {
                    restrict(this.auditFile, "rw-------");
                }
            } catch (IOException e) {
                this.logger.warning("Could not write the SSH audit log: " + e.getMessage());
            }
        }
    }

    /* ------------------------------------------------------------------ helpers */

    private SshServer put(SshServer server) throws IOException {
        this.servers.put(server.name(), server);
        this.save();
        return server;
    }

    private void save() throws IOException {
        JSONArray array = new JSONArray();
        this.servers.values().forEach(server -> array.put(server.toJson()));
        writePrivate(this.serversFile, array.toString(2));
    }

    private static String installer() throws IOException {
        try (InputStream in = SshKeyStore.class.getResourceAsStream("/ssh/" + INSTALLER)) {
            if (in == null) {
                throw new IOException(INSTALLER + " missing in the plugin");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void writePrivate(Path file, String content) throws IOException {
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, content, StandardCharsets.UTF_8);
        restrict(temp, "rw-------");
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void restrict(Path path, String permissions) {
        try {
            Set<PosixFilePermission> set = PosixFilePermissions.fromString(permissions);
            Files.setPosixFilePermissions(path, set);
        } catch (UnsupportedOperationException | IOException ignored) {
            /* not a POSIX file system */
        }
    }

    private static void check(Pattern pattern, String value, String what) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid " + what + ": " + value);
        }
    }

    /**
     * Single quotes for the remote shell; the values are validated before and contain no quotes.
     */
    private static String quote(String value) {
        if (value.contains("'")) {
            throw new IllegalArgumentException("Invalid value");
        }
        return "'" + value + "'";
    }
}
