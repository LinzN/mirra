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

package de.linzn.mirra.identitySystem;

import de.linzn.stem.modules.pluginModule.STEMPlugin;

import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * User profiles of Mirra. Every person is one profile with any number of chat channels (Discord, WhatsApp).
 * Unknown senders get a guest profile automatically (if enabled), so Mirra remembers them; an administrator can
 * later link their channels to an existing profile, merge profiles or grant permissions.
 */
public class IdentityManager {

    /* last seen times are written at most this often per channel */
    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(10);

    private final STEMPlugin plugin;
    private final UserRepository repository;
    private final boolean autoCreateGuests;
    private final Map<Integer, IdentityUser> users = new ConcurrentHashMap<>();
    private final Map<String, UserToken> channels = new ConcurrentHashMap<>();
    /* senders without a profile when guests are not created automatically */
    private final Map<String, UserToken> anonymous = new ConcurrentHashMap<>();
    private final Map<Integer, LocalDateTime> touched = new ConcurrentHashMap<>();

    public IdentityManager(STEMPlugin plugin, UserRepository repository, boolean autoCreateGuests) {
        this.plugin = plugin;
        this.repository = repository;
        this.autoCreateGuests = autoCreateGuests;
        this.reload();
    }

    /**
     * Load all profiles from the database.
     */
    public synchronized void reload() {
        try {
            Map<Integer, IdentityUser> loaded = this.repository.loadUsers();
            this.users.clear();
            this.channels.clear();
            this.users.putAll(loaded);
            for (IdentityUser user : loaded.values()) {
                for (UserToken token : user.getUserTokens()) {
                    this.channels.put(key(token.getSource(), token.getName()), token);
                }
            }
            long guests = loaded.values().stream().filter(IdentityUser::isGuest).count();
            this.plugin.getPluginLogger().info("Loaded " + (loaded.size() - guests) + " user profiles and " + guests + " guest profiles");
        } catch (SQLException e) {
            this.plugin.getPluginLogger().error("Unable to load user profiles", e);
        }
    }

    public UserRepository getRepository() {
        return repository;
    }

    /**
     * @return all profiles, registered users first
     */
    public List<IdentityUser> getIdentities() {
        List<IdentityUser> list = new ArrayList<>(this.users.values());
        list.sort(Comparator.comparing(IdentityUser::isGuest).thenComparing(user -> user.getIdentityName().toLowerCase(Locale.ROOT)));
        return list;
    }

    /**
     * @return the stored channel with this id
     */
    public Optional<UserToken> findChannel(int channelId) {
        return this.channels.values().stream().filter(token -> token.getId() == channelId).findFirst();
    }

    public Optional<IdentityUser> findById(int id) {
        return Optional.ofNullable(this.users.get(id));
    }

    /**
     * Find a profile by its name, nickname or full name, ignoring case.
     */
    public Optional<IdentityUser> findIdentity(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String wanted = name.trim();
        Optional<IdentityUser> exact = this.users.values().stream().filter(user -> user.getIdentityName().equalsIgnoreCase(wanted)).findFirst();
        if (exact.isPresent()) {
            return exact;
        }
        List<IdentityUser> matches = this.users.values().stream()
                .filter(user -> user.field("nickname").map(wanted::equalsIgnoreCase).orElse(false)
                        || user.field("full_name").map(wanted::equalsIgnoreCase).orElse(false))
                .toList();
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    public IdentityUser getIdentityUserByToken(UserToken userToken) {
        if (userToken != null && userToken.getUserId() >= 0) {
            IdentityUser user = this.users.get(userToken.getUserId());
            if (user != null) {
                return user;
            }
        }
        return new IdentityGuest(userToken);
    }

    /**
     * @see #resolve(String, TokenSource, String)
     */
    public UserToken getOrCreateUserToken(String address, TokenSource source) {
        return this.resolve(address, source, null);
    }

    /**
     * Find the channel of a sender. Unknown senders get a guest profile with this channel if guests are created
     * automatically, otherwise an anonymous token.
     *
     * @param displayName name shown on the channel, used for the guest profile and kept up to date
     */
    public UserToken resolve(String address, TokenSource source, String displayName) {
        if (source == TokenSource.INTERNAL) {
            return UserToken.internal(address);
        }
        UserToken token = this.channels.get(key(source, address));
        if (token == null && this.autoCreateGuests) {
            token = this.createGuest(address, source, displayName);
        }
        if (token == null) {
            return this.anonymous.computeIfAbsent(key(source, address), key -> new UserToken(-1, address, source));
        }
        this.touch(token, displayName);
        return token;
    }

    private synchronized UserToken createGuest(String address, TokenSource source, String displayName) {
        UserToken existing = this.channels.get(key(source, address));
        if (existing != null) {
            return existing;
        }
        try {
            String name = this.uniqueName(displayName == null || displayName.isBlank() ? "Guest" : displayName.trim());
            int userId = this.repository.insertUser(name, true);
            int channelId = this.repository.insertChannel(userId, source, address, displayName);
            IdentityUser user = new IdentityUser(userId, name, true);
            user.setCreated(LocalDateTime.now());
            UserToken token = new UserToken(channelId, address, source, userId, displayName);
            user.assignUserToken(token);
            this.users.put(userId, user);
            this.channels.put(key(source, address), token);
            this.plugin.getPluginLogger().info("New guest profile '" + name + "' for " + token);
            return token;
        } catch (SQLException e) {
            this.plugin.getPluginLogger().error("Could not create guest profile for " + source + ":" + address, e);
            return null;
        }
    }

    private void touch(UserToken token, String displayName) {
        if (token.getId() < 0 || token.getUserId() < 0) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        boolean nameChanged = displayName != null && !displayName.isBlank() && !displayName.equals(token.getDisplayName());
        LocalDateTime last = this.touched.get(token.getId());
        if (!nameChanged && last != null && Duration.between(last, now).compareTo(TOUCH_INTERVAL) < 0) {
            return;
        }
        this.touched.put(token.getId(), now);
        if (nameChanged) {
            token.setDisplayName(displayName);
        }
        IdentityUser user = this.users.get(token.getUserId());
        if (user != null) {
            user.setLastSeen(now);
        }
        this.plugin.getStemApi().scheduler().runAsync(() -> {
            try {
                this.repository.touch(token.getUserId(), token.getId(), nameChanged ? displayName : null);
            } catch (SQLException e) {
                this.plugin.getPluginLogger().debug("Could not update last seen: " + e.getMessage());
            }
        });
    }

    /* ---------- administration ---------- */

    public synchronized IdentityUser createUser(String name) throws SQLException {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A user needs a name");
        }
        if (this.users.values().stream().anyMatch(user -> user.getIdentityName().equalsIgnoreCase(name.trim()))) {
            throw new IllegalArgumentException("A user named '" + name + "' already exists");
        }
        int id = this.repository.insertUser(name.trim(), false);
        IdentityUser user = new IdentityUser(id, name.trim(), false);
        user.setCreated(LocalDateTime.now());
        this.users.put(id, user);
        return user;
    }

    /**
     * Assign a channel to a user. If the channel belongs to a guest profile with no other channels, the guest
     * profile is merged into the user (so its memories are kept); otherwise the channel is moved.
     */
    public synchronized void linkChannel(IdentityUser user, TokenSource source, String address) throws SQLException {
        UserToken existing = this.channels.get(key(source, address));
        if (existing == null) {
            int channelId = this.repository.insertChannel(user.getId(), source, address, null);
            UserToken token = new UserToken(channelId, address, source, user.getId(), null);
            user.assignUserToken(token);
            this.channels.put(key(source, address), token);
            return;
        }
        if (existing.getUserId() == user.getId()) {
            return;
        }
        IdentityUser owner = this.users.get(existing.getUserId());
        if (owner != null && owner.isGuest() && owner.getUserTokens().size() == 1) {
            this.mergeUsers(owner, user);
            return;
        }
        this.repository.moveChannel(existing.getId(), user.getId());
        if (owner != null) {
            owner.removeUserToken(existing);
        }
        existing.setUserId(user.getId());
        user.assignUserToken(existing);
    }

    public synchronized void unlinkChannel(IdentityUser user, UserToken token) throws SQLException {
        if (!user.hasUserToken(token)) {
            throw new IllegalArgumentException("The channel does not belong to " + user.getIdentityName());
        }
        this.repository.deleteChannel(token.getId());
        user.removeUserToken(token);
        this.channels.remove(key(token.getSource(), token.getName()));
    }

    /**
     * Move channels, permissions, attributes, reminders and memories of {@code from} to {@code into} and delete {@code from}.
     */
    public synchronized void mergeUsers(IdentityUser from, IdentityUser into) throws SQLException {
        if (from.getId() == into.getId()) {
            throw new IllegalArgumentException("Cannot merge a user with itself");
        }
        this.repository.mergeUsers(from.getId(), into.getId());
        this.reload();
    }

    public void setPermission(IdentityUser user, AiPermissions permission, boolean granted) throws SQLException {
        this.repository.setPermission(user.getId(), permission, granted);
        if (granted) {
            user.addPermission(permission);
        } else {
            user.removePermission(permission);
        }
    }

    public void setGuest(IdentityUser user, boolean guest) throws SQLException {
        this.repository.setGuest(user.getId(), guest);
        user.setGuest(guest);
    }

    /**
     * Set a profile field (see {@link IdentityUser#PROFILE_FIELDS}) or, for any other key, an attribute.
     *
     * @param value  null or empty to remove
     * @param source who set it: user, admin or auto
     */
    public void setProfileValue(IdentityUser user, String key, String value, String source) throws SQLException {
        String normalized = normalizeKey(key);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Empty profile key");
        }
        String clean = value == null ? null : value.trim();
        if (clean != null && clean.length() > 2000) {
            clean = clean.substring(0, 2000);
        }
        if (IdentityUser.PROFILE_FIELDS.contains(normalized)) {
            if (normalized.equals("preferred_channel") && clean != null && !clean.isBlank()) {
                TokenSource.valueOf(clean.toUpperCase(Locale.ROOT));
                clean = clean.toLowerCase(Locale.ROOT);
            }
            this.repository.setField(user.getId(), normalized, clean);
            user.setField(normalized, clean);
        } else {
            this.repository.setAttribute(user.getId(), normalized, clean, source);
            user.setAttribute(normalized, clean);
        }
    }

    public static String normalizeKey(String key) {
        return key == null ? "" : key.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_").replaceAll("^_+|_+$", "");
    }

    private String uniqueName(String base) {
        String name = base.length() > 60 ? base.substring(0, 60) : base;
        String candidate = name;
        int counter = 2;
        while (this.isTaken(candidate)) {
            candidate = name + " (" + counter++ + ")";
        }
        return candidate;
    }

    private boolean isTaken(String name) {
        return this.users.values().stream().anyMatch(user -> user.getIdentityName().equalsIgnoreCase(name));
    }

    private static String key(TokenSource source, String address) {
        return source.name() + ":" + address.toLowerCase(Locale.ROOT);
    }
}
