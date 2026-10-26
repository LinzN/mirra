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

package de.linzn.mirra.whatsapp.listener;

import de.linzn.evolutionApiJava.EvolutionApi;
import de.linzn.evolutionApiJava.api.JidClient;
import de.linzn.evolutionApiJava.api.MediaAttachment;
import de.linzn.evolutionApiJava.api.TextMessage;
import de.linzn.evolutionApiJava.event.EventPriority;
import de.linzn.evolutionApiJava.event.EventSettings;
import de.linzn.evolutionApiJava.event.defaultEvents.NewMessageEvent;
import de.linzn.mirra.MirraPlugin;
import de.linzn.mirra.agent.ChatContext;
import de.linzn.mirra.agent.attachment.Attachment;
import de.linzn.mirra.agent.attachment.AttachmentProcessor;
import de.linzn.mirra.identitySystem.TokenSource;
import de.linzn.mirra.identitySystem.UserToken;

import java.util.Base64;
import java.util.List;

public class OnNewChatMessageListener {

    private final MirraPlugin plugin;
    private final EvolutionApi evolutionApi;

    public OnNewChatMessageListener(MirraPlugin plugin, EvolutionApi evolutionApi) {
        this.plugin = plugin;
        this.evolutionApi = evolutionApi;
    }

    @EventSettings(priority = EventPriority.NORMAL)
    public void onNewMessage(NewMessageEvent event) {
        TextMessage textMessage = event.textMessage();
        if (textMessage == null || textMessage.fromMe() || textMessage.isBroadcast()) {
            return;
        }
        JidClient senderJid = textMessage.remoteClientId();
        String senderName = textMessage.pushName();
        String content = textMessage.text() == null ? "" : textMessage.text();
        if (senderJid == null || (content.isBlank() && !textMessage.hasMedia())) {
            return;
        }
        /* the agent may run several tools, never block the RabbitMQ consumer */
        this.plugin.getStemApi().scheduler().runAsync(() -> {
            try {
                this.answer(senderName, content, senderJid, textMessage);
            } catch (Exception e) {
                this.plugin.getPluginLogger().error("WhatsApp AI request failed", e);
            }
        });
    }

    private void answer(String sender, String content, JidClient identifier, TextMessage textMessage) {
        this.plugin.getPluginLogger().info("WhatsApp message from " + sender + (textMessage.hasMedia() ? " with " + textMessage.media().type() : ""));
        this.plugin.getStemApi().scheduler().runAsync(() -> {
            try {
                evolutionApi.getWebApiProvider().SetOnlineOffline(true);
                evolutionApi.getWebApiProvider().sendTypingPresence(identifier, 2500);
            } catch (Exception e) {
                this.plugin.getPluginLogger().debug("Unable to set WhatsApp presence: " + e.getMessage());
            }
        });
        UserToken userToken = this.plugin.getIdentityManager().getOrCreateUserToken(identifier.toString(), TokenSource.WHATSAPP);
        ChatContext chat = this.plugin.chatContext(userToken, sender == null || sender.isBlank() ? "WhatsApp" : sender);
        List<Attachment> attachments = textMessage.hasMedia() ? List.of(this.download(textMessage)) : List.of();
        String answer = this.plugin.getAgent().chat(chat, content, attachments);
        try {
            this.plugin.deliverAnswer(chat, answer, textMessage.hasMedia() && textMessage.media().voice());
        } finally {
            evolutionApi.getWebApiProvider().SetOnlineOffline(false);
        }
    }

    /**
     * Load the media of a message. An empty attachment tells the model that the file could not be loaded.
     */
    private Attachment download(TextMessage textMessage) {
        MediaAttachment media = textMessage.media();
        AttachmentProcessor processor = this.plugin.getAttachmentProcessor();
        String fileName = media.fileName();
        if (processor == null || media.size() > processor.maxBytes()) {
            return new Attachment(fileName, media.mimeType(), new byte[0], media.voice());
        }
        try {
            MediaAttachment loaded = evolutionApi.getWebApiProvider().downloadMedia(textMessage);
            byte[] data = Base64.getDecoder().decode(loaded.base64().replaceAll("\\s", ""));
            /* "sticker" and "image" without a name get one from the MIME type */
            return new Attachment(loaded.fileName() != null ? loaded.fileName() : fileName, loaded.mimeType(), data, media.voice());
        } catch (Exception e) {
            this.plugin.getPluginLogger().warning("Could not download WhatsApp media: " + e.getMessage());
            return new Attachment(fileName, media.mimeType(), new byte[0], media.voice());
        }
    }
}
