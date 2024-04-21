package de.linzn.mirra.whatsapp.listener;

import de.linzn.mirra.MirraPlugin;
import de.stem.stemSystem.STEMSystemApp;
import it.auties.whatsapp.api.Whatsapp;
import it.auties.whatsapp.listener.OnWhatsappNewMessage;
import it.auties.whatsapp.model.contact.Contact;
import it.auties.whatsapp.model.info.MessageInfo;
import it.auties.whatsapp.model.jid.Jid;
import it.auties.whatsapp.model.message.model.MessageContainer;
import it.auties.whatsapp.model.message.model.MessageType;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

public class OnNewChatMessageListener implements OnWhatsappNewMessage {


    @Override
    public void onNewMessage(Whatsapp whatsapp, MessageInfo info) {
        whatsapp.changePresence(true);
        Optional<Contact> contact = whatsapp.store().findContactByJid(info.senderJid());
        assignGPTModel(contact.get().fullName().get(), info.message(), info.senderJid(), whatsapp);
        whatsapp.changePresence(false);
    }

    private void assignGPTModel(String sender, MessageContainer message, Jid identifier, Whatsapp whatsapp) {
        STEMSystemApp.LOGGER.INFO("Receive Whatsapp input for AI model");
        if (message.deepType() == MessageType.TEXT) {
            List<String> input = MirraPlugin.mirraPlugin.getAiManager().getDefaultModel().buildMessageBlock(sender, message.textWithNoContextMessage().get(), "WHATSAPP");
            String chatMessage = MirraPlugin.mirraPlugin.getAiManager().getDefaultModel().requestChatCompletion(input, identifier.toString());
            STEMSystemApp.LOGGER.INFO("Response fom AI model received.");
            STEMSystemApp.LOGGER.CORE(chatMessage);
            whatsapp.sendMessage(identifier, chatMessage.replace(".", ".\u00AD"));
        } else {
            STEMSystemApp.LOGGER.WARNING("Not supported yet. Only text input.");
            whatsapp.sendMessage(identifier, "This input is not supported yet!");
        }
    }
}

