package de.linzn.mirra.core.reminder;

import de.linzn.mirra.MirraPlugin;
import de.linzn.mirra.events.MirraReminderEvent;
import de.linzn.mirra.identitySystem.IdentityUser;
import de.linzn.mirra.identitySystem.TokenSource;
import de.linzn.mirra.identitySystem.UserToken;
import de.stem.stemSystem.STEMSystemApp;
import de.stem.stemSystem.modules.databaseModule.DatabaseModule;

import java.sql.*;
import java.util.Date;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

public class ReminderEngine implements Runnable {

    private final ConcurrentLinkedQueue<MirraReminder> activeReminderQueue;

    public ReminderEngine() {
        this.activeReminderQueue = new ConcurrentLinkedQueue<>();
        this.loadMirraReminders();
        STEMSystemApp.getInstance().getScheduler().runRepeatScheduler(MirraPlugin.mirraPlugin, this, 5000, 200, TimeUnit.MILLISECONDS);
    }

    private void loadMirraReminders() {
        DatabaseModule databaseModule = STEMSystemApp.getInstance().getDatabaseModule();
        try {
            Connection conn = databaseModule.getConnection();

            String query = "SELECT * FROM plugin_mirra_reminder where active = '" + 1 + "'";
            Statement st = conn.createStatement();
            ResultSet rs = st.executeQuery(query);

            while (rs.next()) {
                int reminderID = rs.getInt("id");
                String userTokenId = rs.getString("user_token_id");
                String reminderContent = rs.getString("reminder_content");
                Date reminderDatetime = rs.getTimestamp("reminder_datetime");

                String internalQuery = "SELECT * FROM plugin_mirra_identity_user_tokens WHERE id = '" + userTokenId + "' ";
                Statement internalST = conn.createStatement();
                ResultSet internalRS = internalST.executeQuery(internalQuery);
                UserToken userToken = null;
                if (internalRS.next()) {
                    String identityToken = internalRS.getString("identity_token");
                    String tokenSource = internalRS.getString("token_source");
                    userToken = MirraPlugin.mirraPlugin.getIdentityManager().getOrCreateUserToken(identityToken, TokenSource.valueOf(tokenSource));
                }

                IdentityUser identityUser = MirraPlugin.mirraPlugin.getIdentityManager().getIdentityUserByToken(userToken);

                MirraReminder mirraReminder = new MirraReminder(reminderID, identityUser, userToken, reminderContent, reminderDatetime);
                this.activeReminderQueue.add(mirraReminder);
            }
            databaseModule.releaseConnection(conn);
        } catch (SQLException e) {
            STEMSystemApp.LOGGER.ERROR(e);
        }
    }

    public MirraReminder createMirraReminder(IdentityUser identityUser, UserToken userToken, String reminderContent, Date reminderDatetime) {
        DatabaseModule databaseModule = STEMSystemApp.getInstance().getDatabaseModule();
        int reminderID = -1;
        try {
            Connection conn = databaseModule.getConnection();

            String query = "INSERT INTO plugin_mirra_reminder (user_token_id, reminder_content, reminder_datetime, active, created)"
                    + " VALUES (?, ?, ?, ?, ?)";
            PreparedStatement preparedStmt = conn.prepareStatement(query, Statement.RETURN_GENERATED_KEYS);
            preparedStmt.setInt(1, userToken.getId());
            preparedStmt.setString(2, reminderContent);
            preparedStmt.setTimestamp(3, new Timestamp(reminderDatetime.getTime()));
            preparedStmt.setInt(4, 1);
            preparedStmt.setTimestamp(5, new Timestamp(new Date().getTime()));
            preparedStmt.executeUpdate();
            ResultSet generatedKeyResult = preparedStmt.getGeneratedKeys();
            if (generatedKeyResult.next()) {
                reminderID = generatedKeyResult.getInt(1);
            }
            databaseModule.releaseConnection(conn);
        } catch (SQLException e) {
            STEMSystemApp.LOGGER.ERROR(e);
        }


        MirraReminder mirraReminder = new MirraReminder(reminderID, identityUser, userToken, reminderContent, reminderDatetime);
        this.activeReminderQueue.add(mirraReminder);
        return mirraReminder;
    }

    public void disableMirraReminder(MirraReminder mirraReminder) {
        this.activeReminderQueue.remove(mirraReminder);
        DatabaseModule databaseModule = STEMSystemApp.getInstance().getDatabaseModule();

        try {
            Connection conn = databaseModule.getConnection();

            String query = " Update plugin_mirra_reminder SET active = ? WHERE id = ?";
            PreparedStatement preparedStmt = conn.prepareStatement(query);
            preparedStmt.setInt(1, 0);
            preparedStmt.setInt(2, mirraReminder.getId());
            preparedStmt.executeUpdate();
            databaseModule.releaseConnection(conn);
        } catch (SQLException e) {
            STEMSystemApp.LOGGER.ERROR(e);
        }
    }

    @Override
    public void run() {
        for (MirraReminder m : this.activeReminderQueue) {
            if (new Date().after(m.getReminderDate())) {
                try {
                    this.disableMirraReminder(m);
                    STEMSystemApp.getInstance().getEventModule().getStemEventBus().fireEvent(new MirraReminderEvent(m));
                } catch (Exception e) {
                    STEMSystemApp.LOGGER.ERROR(e);
                }
            }
        }
    }
}
