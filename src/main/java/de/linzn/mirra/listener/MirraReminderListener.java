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

import com.github.auties00.cobalt.model.jid.Jid;
import de.linzn.mirra.MirraPlugin;
import de.linzn.mirra.events.MirraReminderEvent;
import de.linzn.mirra.identitySystem.TokenSource;
import de.linzn.mirra.openai.IFunctionCall;
import de.linzn.stem.STEMApp;
import de.linzn.stem.modules.eventModule.handler.StemEventHandler;
import org.json.JSONObject;

import java.text.SimpleDateFormat;

public class MirraReminderListener {

    @StemEventHandler()
    public void onMirraReminderEvent(MirraReminderEvent mirraReminderEvent) {
        STEMApp.LOGGER.CORE("Reminder EVENT Triggered!");
        STEMApp.LOGGER.CORE(mirraReminderEvent.getMirraReminder().getContent());
        STEMApp.LOGGER.CORE(new SimpleDateFormat("dd-MM-yyyy HH:mm:ss").format(mirraReminderEvent.getMirraReminder().getReminderDate()));

        IFunctionCall iFunction = MirraPlugin.mirraPlugin.getAiManager().getFunctionProvider().getFunction("trigger_reminder");
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("reminderStatus", "now");
        jsonObject.put("outputLanguage", "German");
        jsonObject.put("targetUsername", mirraReminderEvent.getMirraReminder().getIdentityUser().getIdentityName());
        jsonObject.put("content", mirraReminderEvent.getMirraReminder().getContent());
        jsonObject.put("datetime", new SimpleDateFormat("dd-MM-yyyy HH:mm:ss").format(mirraReminderEvent.getMirraReminder().getReminderDate()));
        String reminder = MirraPlugin.mirraPlugin.getAiManager().getDefaultModel().requestStandaloneFunctionCall(iFunction.functionName(), jsonObject, mirraReminderEvent.getMirraReminder().getUserToken());

        if (mirraReminderEvent.getMirraReminder().getUserToken().getSource() == TokenSource.DISCORD) {
            MirraPlugin.mirraPlugin.getDiscordManager().getJda().retrieveUserById(mirraReminderEvent.getMirraReminder().getUserToken().getName()).complete().openPrivateChannel().complete().sendMessage(reminder).complete();
        } else if (mirraReminderEvent.getMirraReminder().getUserToken().getSource() == TokenSource.WHATSAPP) {
            STEMApp.LOGGER.CORE("Jid reminder:" + Jid.of(mirraReminderEvent.getMirraReminder().getUserToken().getName()));
            MirraPlugin.mirraPlugin.getWhatsappManager().getWhatsapp().sendChatMessage(Jid.of(mirraReminderEvent.getMirraReminder().getUserToken().getName()), reminder);
        }
    }
}
