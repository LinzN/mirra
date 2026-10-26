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

import de.linzn.mirra.MirraPlugin;
import de.linzn.mirra.agent.ChatContext;
import de.linzn.mirra.identitySystem.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static de.linzn.mirra.agent.tools.Schema.*;
import static de.linzn.mirra.agent.tools.SimpleTool.anyOf;
import static de.linzn.mirra.agent.tools.SimpleTool.notGuest;
import static de.linzn.mirra.identitySystem.AiPermissions.*;

/**
 * Tools for user profiles: reading and updating profiles, and user administration.
 */
public final class UserTools {

    private UserTools() {
    }

    public static List<AgentTool> create(MirraPlugin plugin) {
        List<AgentTool> tools = new ArrayList<>();
        IdentityManager users = plugin.getIdentityManager();

        tools.add(new SimpleTool("list_users",
                "List the user profiles: registered users and (optionally) guests that wrote to Mirra, with their chat channels.",
                Schema.object().prop("include_guests", nullable(bool("true to include guest profiles"))).build(),
                anyOf(STEM_READ), (chat, args) -> {
            boolean guests = Boolean.TRUE.equals(optBool(args, "include_guests"));
            JSONArray list = new JSONArray();
            for (IdentityUser user : users.getIdentities()) {
                if (user.isGuest() && !guests) {
                    continue;
                }
                JSONObject entry = user.toJson(chat.has(STEM_ADMIN));
                entry.remove("attributes");
                list.put(entry);
            }
            return new JSONObject().put("users", list);
        }));

        tools.add(new SimpleTool("get_user_profile",
                "Read a user profile: profile fields, stored attributes, chat channels and (for administrators) permissions. "
                        + "Without name the profile of the person you talk to.",
                Schema.object().prop("name", nullable(string("profile name; null for the person you talk to"))).build(),
                notGuest(), (chat, args) -> {
            IdentityUser user = target(users, chat, optString(args, "name"), STEM_READ);
            boolean self = user.getId() == chat.identityUser().getId();
            return user.toJson(self || chat.has(STEM_ADMIN));
        }));

        tools.add(new SimpleTool("update_user_profile",
                "Store or change information in a user profile. Use it when somebody tells you lasting personal data or asks you to "
                        + "change it. Profile fields: " + IdentityUser.PROFILE_FIELDS + " (birthday as YYYY-MM-DD or --MM-DD, "
                        + "preferred_channel discord or whatsapp, language e.g. de). Any other key is stored as attribute, "
                        + "e.g. email, phone, address, car, employer, allergies. A null value deletes the entry. "
                        + "Changing other people's profiles needs STEM_ADMIN.",
                Schema.object()
                        .prop("name", nullable(string("profile name; null for the person you talk to")))
                        .prop("key", string("profile field or attribute key (snake_case)"))
                        .prop("value", nullable(string("new value; null to delete")))
                        .build(),
                notGuest(), (chat, args) -> {
            IdentityUser user = target(users, chat, optString(args, "name"), STEM_ADMIN);
            String key = requireString(args, "key");
            users.setProfileValue(user, key, optString(args, "value"), user.getId() == chat.identityUser().getId() ? "user" : "admin");
            return new JSONObject().put("saved", true).put("profile", user.toJson(false));
        }));

        /* access requests of new guests */
        tools.add(new SimpleTool("list_access_requests",
                "List the people waiting for approval to chat with Mirra, with channel, number or Discord name and first message.",
                Schema.empty(), anyOf(STEM_ADMIN), (chat, args) -> {
            JSONArray list = new JSONArray();
            for (IdentityUser user : plugin.getAccessControl().pending()) {
                JSONObject entry = new JSONObject().put("name", user.getIdentityName());
                JSONArray channels = new JSONArray();
                user.getUserTokens().forEach(token -> channels.put(AccessControl.describe(token)));
                entry.put("channels", channels);
                String first = user.getAttributes().get("access_request_message");
                if (first != null) {
                    entry.put("first_message", first);
                }
                list.put(entry);
            }
            return new JSONObject().put("pending", list);
        }));

        tools.add(new SimpleTool("decide_access_request",
                "Approve or deny that a person may chat with Mirra (answer to an access request). Approved guests can chat but "
                        + "have no permissions; with register=true they become registered users. Denied people are ignored. "
                        + "Can also be used later to block or unblock somebody.",
                Schema.object()
                        .prop("name", string("profile name from the access request"))
                        .prop("allowed", bool("true to approve, false to deny"))
                        .prop("register", nullable(bool("true to make the person a registered user right away")))
                        .build(),
                anyOf(STEM_ADMIN), (chat, args) -> {
            IdentityUser user = byName(users, requireString(args, "name"));
            boolean allowed = args.getBoolean("allowed");
            if (!allowed && user.getId() == chat.identityUser().getId()) {
                throw new IllegalArgumentException("You cannot block yourself");
            }
            if (allowed && Boolean.TRUE.equals(optBool(args, "register"))) {
                users.setGuest(user, false);
            }
            plugin.getAccessControl().decide(user, allowed, chat.name());
            return new JSONObject().put("saved", true).put("user", user.toJson(true));
        }));

        /* administration */
        tools.add(new SimpleTool("create_user",
                "Create a new registered user profile. Add its channels with link_user_channel and permissions with set_user_permissions.",
                Schema.object().prop("name", string("unique profile name, usually the first name")).build(),
                anyOf(STEM_ADMIN), (chat, args) -> new JSONObject().put("created", users.createUser(requireString(args, "name")).toJson(true))));

        tools.add(new SimpleTool("link_user_channel",
                "Assign a Discord account or WhatsApp number to a user profile. If it belonged to a guest profile, the guest profile "
                        + "is merged into the user (memories are kept).",
                Schema.object()
                        .prop("name", string("profile name"))
                        .prop("channel", enumeration("channel", List.of("discord", "whatsapp")))
                        .prop("address", string("Discord user id, or WhatsApp number with country code (e.g. 4917612345678) or JID"))
                        .build(),
                anyOf(STEM_ADMIN), (chat, args) -> {
            IdentityUser user = byName(users, requireString(args, "name"));
            TokenSource source = TokenSource.valueOf(requireString(args, "channel").toUpperCase(Locale.ROOT));
            users.linkChannel(user, source, address(source, requireString(args, "address")));
            plugin.getAgent().getMemory().load();
            return new JSONObject().put("linked", true).put("user", byName(users, user.getIdentityName()).toJson(true));
        }));

        tools.add(new SimpleTool("unlink_user_channel",
                "Remove a channel from a user profile.",
                Schema.object()
                        .prop("name", string("profile name"))
                        .prop("channel", enumeration("channel", List.of("discord", "whatsapp")))
                        .prop("address", nullable(string("address if the user has several channels of this kind")))
                        .build(),
                anyOf(STEM_ADMIN), (chat, args) -> {
            IdentityUser user = byName(users, requireString(args, "name"));
            TokenSource source = TokenSource.valueOf(requireString(args, "channel").toUpperCase(Locale.ROOT));
            String address = optString(args, "address");
            List<UserToken> matches = user.getUserTokens().stream()
                    .filter(token -> token.getSource() == source && (address == null || token.getName().equalsIgnoreCase(address(source, address))))
                    .toList();
            if (matches.size() != 1) {
                throw new IllegalArgumentException(matches.isEmpty() ? "No such channel" : "Several channels, give the address");
            }
            users.unlinkChannel(user, matches.get(0));
            return new JSONObject().put("unlinked", true);
        }));

        tools.add(new SimpleTool("list_permissions",
                "List all permissions with what they allow and which tools they unlock, and optionally who has them. Use it to "
                        + "map requests like 'Dennis darf Lichter schalten' or 'Anna soll keine Bilder mehr machen' to permissions.",
                Schema.object().prop("with_users", nullable(bool("true to also list which users have each permission"))).build(),
                anyOf(STEM_ADMIN), (chat, args) -> {
            boolean withUsers = Boolean.TRUE.equals(optBool(args, "with_users"));
            JSONArray list = new JSONArray();
            for (AiPermissions permission : AiPermissions.values()) {
                JSONObject entry = new JSONObject().put("permission", permission.name()).put("allows", permission.getDescription())
                        .put("tools", new JSONArray(toolsOf(plugin, permission)));
                if (withUsers) {
                    JSONArray holders = new JSONArray();
                    users.getIdentities().stream().filter(user -> !user.isGuest() && user.getPermissions().contains(permission))
                            .forEach(user -> holders.put(user.getIdentityName()));
                    entry.put("users", holders);
                }
                list.put(entry);
            }
            return new JSONObject().put("permissions", list);
        }));

        tools.add(new SimpleTool("set_user_permissions",
                "Grant and/or revoke permissions of a user, e.g. when an administrator says 'Dennis darf Lichter schalten' "
                        + "(SWITCH_LIGHT, usually together with STATUS_LIGHT) or 'Dennis darf keine Lichter mehr schalten'. "
                        + "Check list_permissions if unsure which permission fits. A guest who gets a permission becomes a "
                        + "registered user. SHELL can only be granted or revoked by somebody who has SHELL. Changes apply immediately.",
                Schema.object()
                        .prop("name", string("profile name"))
                        .prop("grant", array("permissions to grant", enumeration("permission", permissionNames())))
                        .prop("revoke", array("permissions to revoke", enumeration("permission", permissionNames())))
                        .prop("notify", nullable(bool("true to tell the user about the change")))
                        .build(),
                anyOf(STEM_ADMIN), (chat, args) -> {
            IdentityUser user = byName(users, requireString(args, "name"));
            List<AiPermissions> grant = permissions(args.optJSONArray("grant"));
            List<AiPermissions> revoke = permissions(args.optJSONArray("revoke"));
            if ((grant.contains(SHELL) || revoke.contains(SHELL)) && !chat.has(SHELL)) {
                throw new IllegalArgumentException("Only users with SHELL can grant or revoke SHELL");
            }
            if (user.getId() == chat.identityUser().getId() && revoke.contains(STEM_ADMIN)) {
                throw new IllegalArgumentException("You cannot remove your own STEM_ADMIN permission");
            }
            if (!grant.isEmpty() && user.isGuest()) {
                users.setGuest(user, false);
            }
            for (AiPermissions permission : grant) {
                users.setPermission(user, permission, true);
            }
            for (AiPermissions permission : revoke) {
                users.setPermission(user, permission, false);
            }
            plugin.getPluginLogger().info(chat.name() + " changed permissions of " + user.getIdentityName() + ": +" + grant + " -" + revoke);
            if (Boolean.TRUE.equals(optBool(args, "notify")) && (!grant.isEmpty() || !revoke.isEmpty())) {
                StringBuilder text = new StringBuilder("ℹ️ " + chat.name() + " hat deine Rechte bei Mirra geändert.");
                grant.forEach(permission -> text.append("\n✅ ").append(permission.getDescription()));
                revoke.forEach(permission -> text.append("\n❌ ").append(permission.getDescription()));
                plugin.getMessenger().tokenOf(user, null).ifPresent(token -> {
                    try {
                        plugin.getMessenger().sendText(token, text.toString());
                        plugin.getAgent().recordOutgoing(token.getName(), text.toString());
                    } catch (Exception e) {
                        plugin.getPluginLogger().warning("Could not notify " + user.getIdentityName() + ": " + e.getMessage());
                    }
                });
            }
            JSONArray now = new JSONArray();
            user.getPermissions().forEach(permission -> now.put(permission.name()));
            return new JSONObject().put("saved", true).put("granted", new JSONArray(grant)).put("revoked", new JSONArray(revoke))
                    .put("permissions_now", now).put("registered", !user.isGuest());
        }));

        tools.add(new SimpleTool("set_user_registered",
                "Turn a guest profile into a registered user (or back into a guest). Guests have no permissions besides the STEM status.",
                Schema.object()
                        .prop("name", string("profile name"))
                        .prop("registered", bool("true for a registered user, false for a guest"))
                        .build(),
                anyOf(STEM_ADMIN), (chat, args) -> {
            IdentityUser user = byName(users, requireString(args, "name"));
            users.setGuest(user, !args.getBoolean("registered"));
            return new JSONObject().put("saved", true).put("user", user.toJson(true));
        }));

        tools.add(new SimpleTool("merge_users",
                "Merge two profiles of the same person, e.g. a guest profile from WhatsApp into the registered user. Channels, "
                        + "permissions, attributes, reminders and memories move to the target, the source profile is deleted.",
                Schema.object()
                        .prop("from", string("profile that is merged and deleted"))
                        .prop("into", string("profile that is kept"))
                        .build(),
                anyOf(STEM_ADMIN), (chat, args) -> {
            IdentityUser from = byName(users, requireString(args, "from"));
            IdentityUser into = byName(users, requireString(args, "into"));
            users.mergeUsers(from, into);
            plugin.getAgent().getMemory().load();
            plugin.getPluginLogger().info(chat.name() + " merged " + from.getIdentityName() + " into " + into.getIdentityName());
            return new JSONObject().put("merged", true).put("user", byName(users, into.getIdentityName()).toJson(true));
        }));
        return tools;
    }

    private static List<String> permissionNames() {
        return java.util.Arrays.stream(AiPermissions.values()).map(Enum::name).toList();
    }

    private static List<AiPermissions> permissions(JSONArray names) {
        List<AiPermissions> list = new ArrayList<>();
        if (names != null) {
            for (int i = 0; i < names.length(); i++) {
                list.add(AiPermissions.valueOf(names.getString(i).trim().toUpperCase(Locale.ROOT)));
            }
        }
        return list;
    }

    /**
     * Tools a registered user with only this permission may use, computed from the tool registry.
     */
    private static List<String> toolsOf(MirraPlugin plugin, AiPermissions permission) {
        IdentityUser probe = new IdentityUser("probe");
        probe.addPermission(permission);
        ChatContext context = new ChatContext(UserToken.probe(TokenSource.DISCORD), probe, "probe");
        IdentityUser nobody = new IdentityUser("nobody");
        ChatContext baseline = new ChatContext(UserToken.probe(TokenSource.DISCORD), nobody, "nobody");
        return plugin.getAgent().getTools().getTools().stream()
                .filter(tool -> tool.isAllowed(context) && !tool.isAllowed(baseline))
                .map(AgentTool::name).sorted().toList();
    }

    private static IdentityUser target(IdentityManager users, ChatContext chat, String name, AiPermissions forOthers) {
        if (name == null || name.isBlank() || name.equalsIgnoreCase(chat.identityUser().getIdentityName())) {
            if (chat.identityUser().getId() < 0) {
                throw new IllegalArgumentException("You talk to somebody without profile");
            }
            return chat.identityUser();
        }
        if (!chat.has(forOthers)) {
            throw new IllegalArgumentException("Profiles of other people need the " + forOthers + " permission");
        }
        return byName(users, name);
    }

    private static IdentityUser byName(IdentityManager users, String name) {
        return users.findIdentity(name).orElseThrow(() -> new IllegalArgumentException("Unknown user '" + name + "', see list_users"));
    }

    /**
     * WhatsApp numbers become JIDs: 0049 / +49 / spaces are removed.
     */
    static String address(TokenSource source, String address) {
        String value = address.trim();
        if (source == TokenSource.WHATSAPP && !value.contains("@")) {
            String digits = value.replaceAll("[^0-9]", "");
            if (digits.startsWith("00")) {
                digits = digits.substring(2);
            }
            if (digits.isEmpty()) {
                throw new IllegalArgumentException("Invalid WhatsApp number");
            }
            return digits + "@s.whatsapp.net";
        }
        return value;
    }
}
