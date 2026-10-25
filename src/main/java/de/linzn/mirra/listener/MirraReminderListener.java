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

package de.linzn.mirra.listener;

import de.linzn.mirra.MirraPlugin;
import de.linzn.mirra.agent.ChatContext;
import de.linzn.mirra.core.reminder.MirraReminder;
import de.linzn.mirra.events.MirraReminderEvent;
import de.linzn.mirra.identitySystem.TokenSource;
import de.linzn.stem.modules.eventModule.handler.StemEventHandler;

import java.text.SimpleDateFormat;

public class MirraReminderListener {

    private final MirraPlugin plugin;

    public MirraReminderListener(MirraPlugin plugin) {
        this.plugin = plugin;
    }

    @StemEventHandler()
    public void onMirraReminderEvent(MirraReminderEvent mirraReminderEvent) {
        MirraReminder reminder = mirraReminderEvent.getMirraReminder();
        String datetime = new SimpleDateFormat("dd.MM.yyyy HH:mm").format(reminder.getReminderDate());
        this.plugin.getPluginLogger().info("Reminder #" + reminder.getId() + " due (" + datetime + ")");
        String name = reminder.getIdentityUser() == null ? "" : reminder.getIdentityUser().getIdentityName();
        String text;
        try {
            text = this.plugin.getAgent().generate("It is time for a reminder you promised to " + name + " (set for " + datetime + "): "
                            + reminder.getContent() + "\nWrite the reminder message to " + name + " in German, short and friendly.",
                    ChatContext.memoryOwner(reminder.getUserToken(), reminder.getIdentityUser()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            text = null;
        } catch (Exception e) {
            this.plugin.getPluginLogger().warning("Could not phrase reminder: " + e.getMessage());
            text = null;
        }
        if (text == null || text.isBlank()) {
            text = "Erinnerung: " + reminder.getContent();
        }
        if (reminder.getUserToken().getSource() == TokenSource.DISCORD) {
            this.plugin.getDiscordManager().sendPrivateMessage(reminder.getUserToken().getName(), text);
        } else if (reminder.getUserToken().getSource() == TokenSource.WHATSAPP) {
            this.plugin.getWhatsappManager().sendTextMessage(reminder.getUserToken().getName(), text);
        }
    }
}
