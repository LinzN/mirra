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

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * A user profile: one person with any number of chat channels, permissions, profile fields and free attributes.
 * Guests are profiles created automatically for unknown senders; they only have {@link AiPermissions#STATUS_STEM}.
 */
public class IdentityUser {

    /**
     * Profile fields with a fixed meaning. Everything else is stored as attribute.
     */
    public static final List<String> PROFILE_FIELDS = List.of(
            "full_name", "nickname", "birthday", "language", "timezone", "location", "preferred_channel", "notes");

    private final int id;
    private final String identityName;
    private final List<UserToken> userTokens = new CopyOnWriteArrayList<>();
    private final Set<AiPermissions> aiPermissionsList = new CopyOnWriteArraySet<>();
    private final Map<String, String> fields = new ConcurrentHashMap<>();
    private final Map<String, String> attributes = new ConcurrentHashMap<>();
    private volatile boolean guest;
    private volatile Access access = Access.APPROVED;

    /**
     * Whether the person may chat with Mirra. New guests wait for the approval of an administrator.
     */
    public enum Access {
        APPROVED, PENDING, DENIED
    }
    private volatile LocalDateTime created;
    private volatile LocalDateTime lastSeen;

    /**
     * Profile that is not stored (fallbacks and tests).
     */
    public IdentityUser(String identityName) {
        this(-1, identityName, false);
    }

    IdentityUser(int id, String identityName, boolean guest) {
        this.id = id;
        this.identityName = identityName;
        this.guest = guest;
    }

    public int getId() {
        return id;
    }

    /**
     * @return the unique name of the profile, used to address the user
     */
    public String getIdentityName() {
        return identityName;
    }

    public boolean isGuest() {
        return guest;
    }

    void setGuest(boolean guest) {
        this.guest = guest;
    }

    public Access getAccess() {
        return access;
    }

    void setAccess(Access access) {
        this.access = access == null ? Access.APPROVED : access;
    }

    public void addPermission(AiPermissions aiPermissions) {
        this.aiPermissionsList.add(aiPermissions);
    }

    void removePermission(AiPermissions aiPermissions) {
        this.aiPermissionsList.remove(aiPermissions);
    }

    public Set<AiPermissions> getPermissions() {
        return Collections.unmodifiableSet(this.aiPermissionsList);
    }

    public boolean hasPermission(AiPermissions aiPermissions) {
        if (this.guest) {
            return aiPermissions == AiPermissions.STATUS_STEM;
        }
        if (this.aiPermissionsList.contains(aiPermissions)) {
            return true;
        }
        /* administrators may read everything they may change */
        return aiPermissions == AiPermissions.STEM_READ && this.aiPermissionsList.contains(AiPermissions.STEM_ADMIN);
    }

    public boolean hasUserToken(UserToken userToken) {
        return this.userTokens.contains(userToken);
    }

    public void assignUserToken(UserToken userToken) {
        if (!this.userTokens.contains(userToken)) {
            this.userTokens.add(userToken);
        }
    }

    void removeUserToken(UserToken userToken) {
        this.userTokens.remove(userToken);
    }

    /**
     * @return the chat channels of the user
     */
    public List<UserToken> getUserTokens() {
        return this.userTokens;
    }

    public Optional<String> field(String name) {
        return Optional.ofNullable(this.fields.get(name));
    }

    void setField(String name, String value) {
        if (value == null || value.isBlank()) {
            this.fields.remove(name);
        } else {
            this.fields.put(name, value);
        }
    }

    public Map<String, String> getAttributes() {
        return Collections.unmodifiableMap(this.attributes);
    }

    void setAttribute(String key, String value) {
        if (value == null || value.isBlank()) {
            this.attributes.remove(key);
        } else {
            this.attributes.put(key, value);
        }
    }

    public LocalDateTime getCreated() {
        return created;
    }

    void setCreated(LocalDateTime created) {
        this.created = created;
    }

    public LocalDateTime getLastSeen() {
        return lastSeen;
    }

    void setLastSeen(LocalDateTime lastSeen) {
        this.lastSeen = lastSeen;
    }

    /**
     * @return the name to use when talking to the person: nickname, first name of the full name or the profile name
     */
    public String getCallName() {
        return this.field("nickname").or(() -> this.field("full_name").map(name -> name.split("\\s+")[0])).orElse(this.identityName);
    }

    /**
     * @param withPrivate include permissions and channel addresses
     */
    public JSONObject toJson(boolean withPrivate) {
        JSONObject json = new JSONObject().put("name", this.identityName).put("guest", this.guest)
                .put("access", this.access.name().toLowerCase(Locale.ROOT));
        JSONObject profile = new JSONObject();
        for (String field : PROFILE_FIELDS) {
            this.field(field).ifPresent(value -> profile.put(field, value));
        }
        json.put("profile", profile);
        json.put("attributes", new JSONObject(this.attributes));
        JSONArray channels = new JSONArray();
        for (UserToken token : this.userTokens) {
            JSONObject channel = new JSONObject().put("channel", token.getSource().name().toLowerCase(Locale.ROOT));
            if (token.getDisplayName() != null) {
                channel.put("display_name", token.getDisplayName());
            }
            if (withPrivate) {
                channel.put("address", token.getName());
            }
            channels.put(channel);
        }
        json.put("channels", channels);
        if (withPrivate) {
            JSONArray permissions = new JSONArray();
            this.aiPermissionsList.forEach(permission -> permissions.put(permission.name()));
            json.put("permissions", permissions);
        }
        if (this.lastSeen != null) {
            json.put("last_seen", this.lastSeen.toString());
        }
        return json;
    }
}
