package de.linzn.mirra.whatsapp.listener;

import de.linzn.mirra.MirraPlugin;
import de.linzn.mirra.identitySystem.IdentityGuest;
import de.linzn.mirra.identitySystem.IdentityUser;
import de.linzn.mirra.identitySystem.TokenSource;
import de.linzn.mirra.identitySystem.UserToken;
import de.stem.stemSystem.STEMSystemApp;
import it.auties.whatsapp.api.Whatsapp;
import it.auties.whatsapp.listener.Listener;
import it.auties.whatsapp.model.contact.Contact;
import it.auties.whatsapp.model.info.MessageInfo;
import it.auties.whatsapp.model.jid.Jid;
import it.auties.whatsapp.model.message.model.MessageType;
import it.auties.whatsapp.model.message.standard.TextMessageBuilder;
import org.json.JSONObject;

import java.util.List;

public class OnNewChatMessageListener implements Listener {


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
            Jid jid = Jid.of(senderJid.toPhoneNumber());
            assignGPTModel(senderName, content, jid, messageType, whatsapp);
        }
        whatsapp.changePresence(false);
    }

    private void assignGPTModel(String sender, String content, Jid identifier, MessageType messageType, Whatsapp whatsapp) {
        STEMSystemApp.LOGGER.INFO("Receive Whatsapp input for AI model");
        if (messageType == MessageType.TEXT) {
            UserToken userToken = MirraPlugin.mirraPlugin.getIdentityManager().getOrCreateUserToken(identifier.toString(), TokenSource.WHATSAPP);
            IdentityUser identityUser = MirraPlugin.mirraPlugin.getIdentityManager().getIdentityUserByToken(userToken);
            if (identityUser instanceof IdentityGuest) {
                ((IdentityGuest) identityUser).setGuestName(sender);
            }
            List<String> input = MirraPlugin.mirraPlugin.getAiManager().getDefaultModel().buildMessageBlock(identityUser.getIdentityName(), content, userToken.getSource().name());
            String chatMessage = MirraPlugin.mirraPlugin.getAiManager().getDefaultModel().requestChatCompletion(input, userToken, sender);
            STEMSystemApp.LOGGER.INFO("Response fom AI model received.");
            STEMSystemApp.LOGGER.CORE(chatMessage);
            TextMessageBuilder textMessageBuilder = new TextMessageBuilder();
            textMessageBuilder.text(chatMessage);
            whatsapp.sendMessage(identifier, textMessageBuilder.build());
        } else {
            STEMSystemApp.LOGGER.WARNING("Not supported yet. Only text input.");
            whatsapp.sendMessage(identifier, "This input is not supported yet!");
        }
    }
}

