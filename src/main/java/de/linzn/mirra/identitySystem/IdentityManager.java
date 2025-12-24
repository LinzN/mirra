/*
 * Copyright (c) 2025 MirraNET, Niklas Linz. All rights reserved.
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

import de.stem.stemSystem.STEMSystemApp;
import de.stem.stemSystem.modules.databaseModule.DatabaseModule;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class IdentityManager {

    private final List<IdentityUser> identities;

    public IdentityManager() {
        this.identities = new ArrayList<>();
        this.loadIdentities();
    }

    private void addIdentityUser(IdentityUser identityUser) {
        STEMSystemApp.LOGGER.CONFIG("ADD IdentityUser: " + identityUser.getIdentityName());
        this.identities.add(identityUser);
    }

    public IdentityUser getIdentityUserByToken(UserToken userToken) {
        IdentityUser user = null;

        for (IdentityUser identityUser : this.identities) {
            if (identityUser.hasUserToken(userToken)) {
                user = identityUser;
                break;
            }
        }
        if (user == null) {
            user = new IdentityGuest(userToken);

        }
        return user;
    }

    private void loadIdentities() {
        DatabaseModule databaseModule = STEMSystemApp.getInstance().getDatabaseModule();
        try {
            Connection conn = databaseModule.getConnection();

            String query = "SELECT * FROM plugin_mirra_identity_user";
            Statement st = conn.createStatement();
            ResultSet rs = st.executeQuery(query);

            while (rs.next()) {
                String id = rs.getString("id");
                String identity_name = rs.getString("identity_name");
                IdentityUser identityUser = new IdentityUser(identity_name);

                String internalQuery = "SELECT * FROM plugin_mirra_identity_user_tokens WHERE identity_user_id = '" + id + "' ";
                Statement internalST = conn.createStatement();
                ResultSet internalRS = internalST.executeQuery(internalQuery);
                while (internalRS.next()) {
                    int identity_id = internalRS.getInt("id");
                    String identity_token = internalRS.getString("identity_token");
                    String tokenSource = internalRS.getString("token_source");
                    UserToken userToken = new UserToken(identity_id, identity_token, TokenSource.valueOf(tokenSource));
                    identityUser.assignUserToken(userToken);
                }
                internalQuery = "SELECT * FROM plugin_mirra_identity_user_permissions WHERE identity_user_id = '" + id + "' ";
                internalST = conn.createStatement();
                internalRS = internalST.executeQuery(internalQuery);

                while (internalRS.next()) {
                    AiPermissions aiPermissions = AiPermissions.valueOf(internalRS.getString("permission"));
                    identityUser.addPermission(aiPermissions);
                }

                this.addIdentityUser(identityUser);
            }
            databaseModule.releaseConnection(conn);
        } catch (SQLException e) {
            STEMSystemApp.LOGGER.ERROR(e);
        }
    }

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
        return null;
    }
}
