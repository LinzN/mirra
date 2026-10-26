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
import de.linzn.mirra.identitySystem.UserRepository;
import de.linzn.mirra.identitySystem.UserToken;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Optional;
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
        UserRepository repository = this.plugin.getIdentityManager().getRepository();
        int count = 0;
        try {
            for (UserRepository.StoredReminder stored : repository.loadActiveReminders()) {
                Optional<UserToken> token = this.plugin.getIdentityManager().findChannel(stored.channelId());
                if (token.isEmpty()) {
                    this.plugin.getPluginLogger().warning("Skip reminder " + stored.id() + ", channel " + stored.channelId() + " not found");
                    continue;
                }
                IdentityUser identityUser = this.plugin.getIdentityManager().getIdentityUserByToken(token.get());
                Date date = Date.from(stored.remindAt().atZone(this.plugin.getStemApi().system().getZone()).toInstant());
                this.activeReminderQueue.add(new MirraReminder(stored.id(), identityUser, token.get(), stored.content(), date));
                count++;
            }
            this.plugin.getPluginLogger().info("Loaded " + count + " active reminders");
        } catch (SQLException e) {
            this.plugin.getPluginLogger().error("Unable to load reminders", e);
        }
    }

    public MirraReminder createMirraReminder(IdentityUser identityUser, UserToken userToken, String reminderContent, Date reminderDatetime) {
        int reminderID = -1;
        try {
            reminderID = this.plugin.getIdentityManager().getRepository().insertReminder(identityUser.getId(), userToken.getId(), reminderContent,
                    LocalDateTime.ofInstant(reminderDatetime.toInstant(), this.plugin.getStemApi().system().getZone()));
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
            this.plugin.getIdentityManager().getRepository().deactivateReminder(mirraReminder.getId());
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
