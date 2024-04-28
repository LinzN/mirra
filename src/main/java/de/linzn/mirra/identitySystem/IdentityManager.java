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

    private final IdentityUser guestUser;
    private final List<IdentityUser> identities;

    public IdentityManager() {
        this.identities = new ArrayList<>();
        this.guestUser = new IdentityUser("Guest");
        this.guestUser.addPermission(AiPermissions.STATUS_STEM);
        this.loadIdentities();
    }

    private void addIdentityUser(IdentityUser identityUser) {
        STEMSystemApp.LOGGER.CONFIG("ADD IdentityUser: " + identityUser.getIdentityName());
        this.identities.add(identityUser);
    }

    public IdentityUser getIdentityUserByToken(String identityToken) {
        IdentityUser user = this.guestUser;

        for (IdentityUser identityUser : this.identities) {
            if (identityUser.hasIdentityToken(identityToken)) {
                user = identityUser;
                break;
            }
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
                    String identity_token = internalRS.getString("identity_token");
                    identityUser.addIdentityToken(identity_token);
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
}
