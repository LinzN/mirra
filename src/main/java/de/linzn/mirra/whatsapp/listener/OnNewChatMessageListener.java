/*
 * Copyright (c) 2025 MirraNET, Niklas Linz. All rights reserved.
 *
 * This file is part of the MirraNET project and is licensed under the
 * GNU Lesser General Public License v3.0 (LGPLv3).
 *
 * You may use, distribute and modify this code under the terms
 * of the LGPLv3 license. You should have received a copy of the
 * license along with this file. If not, see <https://www.gnu.org/licenses/lgpl-3.0.html>
 * or contact: niklas.linz@mirranet.de
 */

package de.linzn.mirra.whatsapp.listener;

import com.github.auties00.cobalt.client.WhatsAppClient;
import com.github.auties00.cobalt.client.WhatsAppClientListener;
import com.github.auties00.cobalt.model.contact.Contact;
import com.github.auties00.cobalt.model.info.MessageInfo;
import com.github.auties00.cobalt.model.jid.Jid;
import com.github.auties00.cobalt.model.message.model.Message;
import com.github.auties00.cobalt.model.message.standard.TextMessage;
import com.github.auties00.cobalt.model.message.standard.TextMessageBuilder;
import de.linzn.mirra.MirraPlugin;
import de.linzn.mirra.identitySystem.IdentityGuest;
import de.linzn.mirra.identitySystem.IdentityUser;
import de.linzn.mirra.identitySystem.TokenSource;
import de.linzn.mirra.identitySystem.UserToken;
import de.stem.stemSystem.STEMSystemApp;
import java.util.List;

public class OnNewChatMessageListener implements WhatsAppClientListener {

    @Override
    public void onNewMessage(WhatsAppClient whatsapp, MessageInfo info) {
        whatsapp.changePresence(true);

        Message.Type messageType = info.message().deepType();
        Jid senderJid = info.senderJid();

        String senderName;
        String content;

        if (messageType == Message.Type.TEXT) {
            if (whatsapp.store().findContactByJid(info.senderJid().withoutData()).isPresent()) {
                Contact contact = whatsapp.store().findContactByJid(info.senderJid().withoutData()).get();
                if (contact.fullName().isPresent()) {
                    senderName = contact.fullName().get();
                    content = ((TextMessage) info.message().content()).text();
                } else {
                    senderName = contact.name();
                    content = ((TextMessage) info.message().content()).text();
                }
                Jid jid = senderJid.withoutData();
                assignGPTModel(senderName, content, jid, messageType, whatsapp);
            }
        }
        whatsapp.changePresence(false);
    }

    private void assignGPTModel(String sender, String content, Jid identifier, Message.Type messageType, WhatsAppClient whatsapp) {
        STEMSystemApp.LOGGER.INFO("Receive Whatsapp input for AI model");
        if (messageType == Message.Type.TEXT) {
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
            //textMessageBuilder.previewType(TextMessage.PreviewType.NONE);
            textMessageBuilder.text(chatMessage);
            STEMSystemApp.LOGGER.CORE("Jid text:" + identifier);
            whatsapp.sendChatMessage(identifier, textMessageBuilder.build().text());
        } else {
            STEMSystemApp.LOGGER.WARNING("Not supported yet. Only text input.");
            whatsapp.sendChatMessage(identifier, "This input is not supported yet!");
        }
    }
}

