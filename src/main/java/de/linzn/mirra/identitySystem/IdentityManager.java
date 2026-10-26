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

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class IdentityManager {

    private final STEMPlugin plugin;
    private final List<IdentityUser> identities;
    /* tokens of unknown users, so a guest keeps the same token (and chat memory) */
    private final Map<String, UserToken> guestTokens;

    public IdentityManager(STEMPlugin plugin) {
        this.plugin = plugin;
        this.identities = new CopyOnWriteArrayList<>();
        this.guestTokens = new ConcurrentHashMap<>();
        this.loadIdentities();
    }

    private void addIdentityUser(IdentityUser identityUser) {
        this.plugin.getPluginLogger().debug("ADD IdentityUser: " + identityUser.getIdentityName());
        this.identities.add(identityUser);
    }

    public IdentityUser getIdentityUserByToken(UserToken userToken) {
        if (userToken != null) {
            for (IdentityUser identityUser : this.identities) {
                if (identityUser.hasUserToken(userToken)) {
                    return identityUser;
                }
            }
        }
        return new IdentityGuest(userToken);
    }

    /**
     * @return all registered identities
     */
    public List<IdentityUser> getIdentities() {
        return List.copyOf(this.identities);
    }

    /**
     * @return the identity with this name, ignoring case
     */
    public Optional<IdentityUser> findIdentity(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String wanted = name.trim();
        return this.identities.stream().filter(identity -> identity.getIdentityName().equalsIgnoreCase(wanted)).findFirst();
    }

    private void loadIdentities() {
        try {
            this.plugin.getStemApi().data().withConnection(conn -> {
                try (PreparedStatement st = conn.prepareStatement("SELECT * FROM plugin_mirra_identity_user");
                     ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        int id = rs.getInt("id");
                        IdentityUser identityUser = new IdentityUser(rs.getString("identity_name"));
                        try (PreparedStatement tokenSt = conn.prepareStatement("SELECT * FROM plugin_mirra_identity_user_tokens WHERE identity_user_id = ?")) {
                            tokenSt.setInt(1, id);
                            try (ResultSet tokenRs = tokenSt.executeQuery()) {
                                while (tokenRs.next()) {
                                    String tokenSource = tokenRs.getString("token_source");
                                    try {
                                        identityUser.assignUserToken(new UserToken(tokenRs.getInt("id"), tokenRs.getString("identity_token"), TokenSource.valueOf(tokenSource)));
                                    } catch (IllegalArgumentException e) {
                                        this.plugin.getPluginLogger().warning("Unknown token source " + tokenSource + " for identity " + identityUser.getIdentityName());
                                    }
                                }
                            }
                        }
                        try (PreparedStatement permissionSt = conn.prepareStatement("SELECT * FROM plugin_mirra_identity_user_permissions WHERE identity_user_id = ?")) {
                            permissionSt.setInt(1, id);
                            try (ResultSet permissionRs = permissionSt.executeQuery()) {
                                while (permissionRs.next()) {
                                    String permission = permissionRs.getString("permission");
                                    try {
                                        identityUser.addPermission(AiPermissions.valueOf(permission));
                                    } catch (IllegalArgumentException e) {
                                        this.plugin.getPluginLogger().warning("Unknown permission " + permission + " for identity " + identityUser.getIdentityName());
                                    }
                                }
                            }
                        }
                        this.addIdentityUser(identityUser);
                    }
                }
                return null;
            });
        } catch (SQLException e) {
            this.plugin.getPluginLogger().error("Unable to load identities", e);
        }
    }

    /**
     * @return the token of a known identity, or a guest token (id -1, not stored in the database)
     */
    public UserToken getOrCreateUserToken(String name, TokenSource source) {
        if (source == TokenSource.INTERNAL) {
            return new UserToken(-1, name, source);
        }
        for (IdentityUser identityUser : this.identities) {
            for (UserToken userToken : identityUser.getUserTokens()) {
                if (userToken.getName().equalsIgnoreCase(name) && userToken.getSource() == source) {
                    return userToken;
                }
            }
        }
        //todo create objects in database and assign guest token
        return this.guestTokens.computeIfAbsent(source.name() + ":" + name.toLowerCase(), key -> new UserToken(-1, name, source));
    }
}
