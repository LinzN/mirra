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
import de.linzn.evolutionApiJava.api.TextMessage;
import de.linzn.evolutionApiJava.event.EventPriority;
import de.linzn.evolutionApiJava.event.EventSettings;
import de.linzn.evolutionApiJava.event.defaultEvents.NewMessageEvent;
import de.linzn.mirra.MirraPlugin;
import de.linzn.mirra.agent.ChatContext;
import de.linzn.mirra.identitySystem.TokenSource;
import de.linzn.mirra.identitySystem.UserToken;

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
        if (textMessage == null || textMessage.fromMe()) {
            return;
        }
        JidClient senderJid = textMessage.remoteClientId();
        String senderName = textMessage.pushName();
        String content = textMessage.text();
        if (senderJid == null || content == null || content.isBlank()) {
            return;
        }
        /* the agent may run several tools, never block the RabbitMQ consumer */
        this.plugin.getStemApi().scheduler().runAsync(() -> {
            try {
                this.answer(senderName, content, senderJid);
            } catch (Exception e) {
                this.plugin.getPluginLogger().error("WhatsApp AI request failed", e);
            }
        });
    }

    private void answer(String sender, String content, JidClient identifier) {
        this.plugin.getPluginLogger().info("WhatsApp message from " + sender);
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
        String answer = this.plugin.getAgent().chat(chat, content);
        try {
            if (answer != null && !answer.isBlank()) {
                evolutionApi.getWebApiProvider().sendTextMessage(identifier, answer);
            }
        } finally {
            evolutionApi.getWebApiProvider().SetOnlineOffline(false);
        }
    }
}
