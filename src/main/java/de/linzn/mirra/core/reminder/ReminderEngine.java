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

package de.linzn.mirra.core.reminder;

import de.linzn.mirra.MirraPlugin;
import de.linzn.mirra.events.MirraReminderEvent;
import de.linzn.mirra.identitySystem.IdentityUser;
import de.linzn.mirra.identitySystem.TokenSource;
import de.linzn.mirra.identitySystem.UserToken;

import java.sql.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

public class ReminderEngine implements Runnable {

    private final MirraPlugin plugin;
    private final ConcurrentLinkedQueue<MirraReminder> activeReminderQueue;

    public ReminderEngine(MirraPlugin plugin) {
        this.plugin = plugin;
        this.activeReminderQueue = new ConcurrentLinkedQueue<>();
        this.loadMirraReminders();
        /* owned by the plugin, stopped on disable */
        plugin.getStemApi().scheduler().runRepeating(this, 5000, 1000, TimeUnit.MILLISECONDS);
    }

    private void loadMirraReminders() {
        try {
            int loaded = this.plugin.getStemApi().data().withConnection(conn -> {
                int count = 0;
                String query = "SELECT r.id, r.reminder_content, r.reminder_datetime, t.identity_token, t.token_source"
                        + " FROM plugin_mirra_reminder r LEFT JOIN plugin_mirra_identity_user_tokens t ON t.id = r.user_token_id"
                        + " WHERE r.active = 1";
                try (PreparedStatement st = conn.prepareStatement(query); ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        int reminderID = rs.getInt("id");
                        String reminderContent = rs.getString("reminder_content");
                        Date reminderDatetime = rs.getTimestamp("reminder_datetime");
                        String identityToken = rs.getString("identity_token");
                        String tokenSource = rs.getString("token_source");
                        UserToken userToken = null;
                        if (identityToken != null && tokenSource != null) {
                            try {
                                userToken = this.plugin.getIdentityManager().getOrCreateUserToken(identityToken, TokenSource.valueOf(tokenSource));
                            } catch (IllegalArgumentException e) {
                                this.plugin.getPluginLogger().warning("Unknown token source " + tokenSource + " for reminder " + reminderID);
                            }
                        }
                        if (userToken == null || reminderDatetime == null) {
                            this.plugin.getPluginLogger().warning("Skip reminder " + reminderID + ", user token or date not found");
                            continue;
                        }
                        IdentityUser identityUser = this.plugin.getIdentityManager().getIdentityUserByToken(userToken);
                        this.activeReminderQueue.add(new MirraReminder(reminderID, identityUser, userToken, reminderContent, reminderDatetime));
                        count++;
                    }
                }
                return count;
            });
            this.plugin.getPluginLogger().info("Loaded " + loaded + " active reminders");
        } catch (SQLException e) {
            this.plugin.getPluginLogger().error("Unable to load reminders", e);
        }
    }

    public MirraReminder createMirraReminder(IdentityUser identityUser, UserToken userToken, String reminderContent, Date reminderDatetime) {
        int reminderID = -1;
        try {
            reminderID = this.plugin.getStemApi().data().withConnection(conn -> {
                String query = "INSERT INTO plugin_mirra_reminder (user_token_id, reminder_content, reminder_datetime, active, created)"
                        + " VALUES (?, ?, ?, ?, ?)";
                try (PreparedStatement preparedStmt = conn.prepareStatement(query, Statement.RETURN_GENERATED_KEYS)) {
                    preparedStmt.setInt(1, userToken.getId());
                    preparedStmt.setString(2, reminderContent);
                    preparedStmt.setTimestamp(3, new Timestamp(reminderDatetime.getTime()));
                    preparedStmt.setInt(4, 1);
                    preparedStmt.setTimestamp(5, new Timestamp(System.currentTimeMillis()));
                    preparedStmt.executeUpdate();
                    try (ResultSet generatedKeyResult = preparedStmt.getGeneratedKeys()) {
                        return generatedKeyResult.next() ? generatedKeyResult.getInt(1) : -1;
                    }
                }
            });
        } catch (SQLException e) {
            this.plugin.getPluginLogger().error("Unable to store reminder", e);
        }
        MirraReminder mirraReminder = new MirraReminder(reminderID, identityUser, userToken, reminderContent, reminderDatetime);
        this.activeReminderQueue.add(mirraReminder);
        return mirraReminder;
    }

    public void disableMirraReminder(MirraReminder mirraReminder) {
        this.activeReminderQueue.remove(mirraReminder);
        if (mirraReminder.getId() < 0) {
            return;
        }
        try {
            this.plugin.getStemApi().data().withConnection(conn -> {
                try (PreparedStatement preparedStmt = conn.prepareStatement("UPDATE plugin_mirra_reminder SET active = ? WHERE id = ?")) {
                    preparedStmt.setInt(1, 0);
                    preparedStmt.setInt(2, mirraReminder.getId());
                    return preparedStmt.executeUpdate();
                }
            });
        } catch (SQLException e) {
            this.plugin.getPluginLogger().error("Unable to disable reminder " + mirraReminder.getId(), e);
        }
    }

    /**
     * @return active reminders of a user token, earliest first
     */
    public List<MirraReminder> getReminders(UserToken userToken) {
        List<MirraReminder> reminders = new ArrayList<>();
        for (MirraReminder reminder : this.activeReminderQueue) {
            if (reminder.getUserToken() != null && reminder.getUserToken().getId() == userToken.getId()
                    && reminder.getUserToken().getSource() == userToken.getSource()
                    && reminder.getUserToken().getName().equals(userToken.getName())) {
                reminders.add(reminder);
            }
        }
        reminders.sort(Comparator.comparing(MirraReminder::getReminderDate));
        return reminders;
    }

    /**
     * Cancel an active reminder of the user token.
     *
     * @return false if no such reminder exists
     */
    public boolean cancelReminder(int id, UserToken userToken) {
        for (MirraReminder reminder : this.getReminders(userToken)) {
            if (reminder.getId() == id) {
                this.disableMirraReminder(reminder);
                return true;
            }
        }
        return false;
    }

    @Override
    public void run() {
        Date now = new Date();
        for (MirraReminder m : this.activeReminderQueue) {
            if (now.after(m.getReminderDate())) {
                try {
                    this.disableMirraReminder(m);
                    this.plugin.getStemApi().events().fire(new MirraReminderEvent(m));
                } catch (Exception e) {
                    this.plugin.getPluginLogger().error("Reminder " + m.getId() + " failed", e);
                }
            }
        }
    }
}
