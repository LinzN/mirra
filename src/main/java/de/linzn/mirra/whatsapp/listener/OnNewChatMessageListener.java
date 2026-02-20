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
import de.linzn.evolutionApiJava.api.Jid;
import de.linzn.evolutionApiJava.api.TextMessage;
import de.linzn.evolutionApiJava.event.EventPriority;
import de.linzn.evolutionApiJava.event.EventSettings;
import de.linzn.evolutionApiJava.event.defaultEvents.NewMessageEvent;
import de.linzn.mirra.MirraPlugin;
import de.linzn.mirra.identitySystem.IdentityGuest;
import de.linzn.mirra.identitySystem.IdentityUser;
import de.linzn.mirra.identitySystem.TokenSource;
import de.linzn.mirra.identitySystem.UserToken;
import de.linzn.stem.STEMApp;

import java.util.List;

public class OnNewChatMessageListener {

    private final EvolutionApi evolutionApi;

    public OnNewChatMessageListener(EvolutionApi evolutionApi) {
        this.evolutionApi = evolutionApi;
    }

    @EventSettings(priority = EventPriority.NORMAL)
    public void onNewMessage(NewMessageEvent event) {
        TextMessage textMessage = event.textMessage();
            if(!textMessage.fromMe()) {
                Jid senderJid = textMessage.remoteJid();
                String senderName = textMessage.pushName();
                String content = textMessage.text();
                assignGPTModel(senderName, content, senderJid);
            } else {
                STEMApp.LOGGER.CORE("Message from AI self?");
            }

    }

    private void assignGPTModel(String sender, String content, Jid identifier) {
        STEMApp.LOGGER.INFO("Input from EvolutionAPI..");
        STEMApp.getInstance().getScheduler().runTask(MirraPlugin.mirraPlugin, () -> {
            evolutionApi.SetOnlineOffline(true);
            evolutionApi.sendTypingPresence(identifier, 2500);
        });
        UserToken userToken = MirraPlugin.mirraPlugin.getIdentityManager().getOrCreateUserToken(identifier.toString(), TokenSource.WHATSAPP);
        IdentityUser identityUser = MirraPlugin.mirraPlugin.getIdentityManager().getIdentityUserByToken(userToken);
        if (identityUser instanceof IdentityGuest) {
            ((IdentityGuest) identityUser).setGuestName(sender);
        }
        List<String> input = MirraPlugin.mirraPlugin.getAiManager().getDefaultModel().buildMessageBlock(identityUser.getIdentityName(), content, userToken.getSource().name());
        String chatMessage = MirraPlugin.mirraPlugin.getAiManager().getDefaultModel().requestChatCompletion(input, userToken, sender);
        STEMApp.LOGGER.INFO("Callback from OpenAI rest api...");
        STEMApp.LOGGER.CORE(chatMessage);
        STEMApp.LOGGER.CORE("Jid text:" + identifier);
        evolutionApi.sendTextMessage(identifier, chatMessage);
        evolutionApi.SetOnlineOffline(false);
    }
}

