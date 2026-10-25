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
import de.linzn.mirra.core.reminder.MirraReminder;
import de.linzn.mirra.events.MirraReminderEvent;
import de.linzn.mirra.identitySystem.TokenSource;
import de.linzn.mirra.openai.IFunctionCall;
import de.linzn.stem.modules.eventModule.handler.StemEventHandler;
import org.json.JSONObject;

import java.text.SimpleDateFormat;

public class MirraReminderListener {

    private final MirraPlugin plugin;

    public MirraReminderListener(MirraPlugin plugin) {
        this.plugin = plugin;
    }

    @StemEventHandler()
    public void onMirraReminderEvent(MirraReminderEvent mirraReminderEvent) {
        MirraReminder reminder = mirraReminderEvent.getMirraReminder();
        String datetime = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss").format(reminder.getReminderDate());
        this.plugin.getPluginLogger().info("Reminder EVENT Triggered! " + datetime);
        this.plugin.getPluginLogger().debug(reminder.getContent());
        IFunctionCall iFunction = this.plugin.getAiManager().getFunctionProvider().getFunction("trigger_reminder");
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("reminderStatus", "now");
        jsonObject.put("outputLanguage", "German");
        jsonObject.put("targetUsername", reminder.getIdentityUser().getIdentityName());
        jsonObject.put("content", reminder.getContent());
        jsonObject.put("datetime", datetime);
        String text = this.plugin.getAiManager().getDefaultModel().requestStandaloneFunctionCall(iFunction.functionName(), jsonObject, reminder.getUserToken());
        if (reminder.getUserToken().getSource() == TokenSource.DISCORD) {
            this.plugin.getDiscordManager().sendPrivateMessage(reminder.getUserToken().getName(), text);
        } else if (reminder.getUserToken().getSource() == TokenSource.WHATSAPP) {
            this.plugin.getWhatsappManager().sendTextMessage(reminder.getUserToken().getName(), text);
        }
    }
}
