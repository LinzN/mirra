package de.linzn.mirra.whatsapp.listener;

import de.linzn.mirra.MirraPlugin;
import de.stem.stemSystem.STEMSystemApp;
import it.auties.whatsapp.api.Whatsapp;
import it.auties.whatsapp.listener.OnWhatsappNewMessage;
import it.auties.whatsapp.model.contact.Contact;
import it.auties.whatsapp.model.info.MessageInfo;
import it.auties.whatsapp.model.jid.Jid;
import it.auties.whatsapp.model.message.model.MessageType;
import org.json.JSONObject;

import java.util.List;

public class OnNewChatMessageListener implements OnWhatsappNewMessage {


    @Override
    public void onNewMessage(Whatsapp whatsapp, MessageInfo info) {
        whatsapp.changePresence(true);

        MessageType messageType = info.message().deepType();
        Jid senderJid = info.senderJid();

        String senderName;
        String content;

        if (whatsapp.store().findContactByJid(info.senderJid()).isPresent()) {
            Contact contact = whatsapp.store().findContactByJid(info.senderJid()).get();
            if (contact.fullName().isPresent()) {
                senderName = contact.fullName().get();
                if (info.message().textWithNoContextMessage().isPresent()) {
                    content = info.message().textWithNoContextMessage().get();
                } else {
                    content = info.message().textMessage().get().text();
                }
            } else {
                senderName = new JSONObject(info.toJson()).getString("pushName");
                if (info.message().textMessage().isPresent()) {
                    content = info.message().textMessage().get().text();
                } else {
                    content = info.message().textWithNoContextMessage().get();
                }
            }
            assignGPTModel(senderName, content, senderJid, messageType, whatsapp);
        }
        whatsapp.changePresence(false);
    }

    private void assignGPTModel(String sender, String content, Jid identifier, MessageType messageType, Whatsapp whatsapp) {
        STEMSystemApp.LOGGER.INFO("Receive Whatsapp input for AI model");
        if (messageType == MessageType.TEXT) {
            List<String> input = MirraPlugin.mirraPlugin.getAiManager().getDefaultModel().buildMessageBlock(sender, content, "WHATSAPP");
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

