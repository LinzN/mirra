package de.linzn.mirra.listener;

import de.linzn.mirra.MirraPlugin;
import de.linzn.mirra.core.functions.IFunction;
import de.linzn.mirra.events.MirraReminderEvent;
import de.linzn.mirra.identitySystem.TokenSource;
import de.stem.stemSystem.STEMSystemApp;
import de.stem.stemSystem.modules.eventModule.handler.StemEventHandler;
import it.auties.whatsapp.model.jid.Jid;
import org.json.JSONObject;

import java.text.SimpleDateFormat;

public class MirraReminderListener {

    @StemEventHandler()
    public void onMirraReminderEvent(MirraReminderEvent mirraReminderEvent) {
        STEMSystemApp.LOGGER.CORE("Reminder EVENT Triggered!");
        STEMSystemApp.LOGGER.CORE(mirraReminderEvent.getMirraReminder().getContent());
        STEMSystemApp.LOGGER.CORE(new SimpleDateFormat("dd-MM-yyyy HH:mm:ss").format(mirraReminderEvent.getMirraReminder().getReminderDate()));

        IFunction iFunction = MirraPlugin.mirraPlugin.getAiManager().getFunctionProvider().getFunction("trigger_reminder");
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
            MirraPlugin.mirraPlugin.getWhatsappManager().getWhatsapp().sendChatMessage(Jid.of(mirraReminderEvent.getMirraReminder().getUserToken().getName()), reminder);
        }
    }
}
