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

import de.linzn.evolutionApiJava.DataListener;
import de.linzn.evolutionApiJava.EvolutionApi;
import de.linzn.evolutionApiJava.api.Jid;
import de.linzn.evolutionApiJava.poolMQ.EventType;
import de.linzn.mirra.MirraPlugin;
import de.linzn.mirra.identitySystem.IdentityGuest;
import de.linzn.mirra.identitySystem.IdentityUser;
import de.linzn.mirra.identitySystem.TokenSource;
import de.linzn.mirra.identitySystem.UserToken;
import de.linzn.stem.STEMApp;
import org.json.JSONObject;

import java.util.List;

public class OnNewChatMessageListener implements DataListener {

    private EvolutionApi evolutionApi;

    public OnNewChatMessageListener(EvolutionApi evolutionApi) {
        this.evolutionApi = evolutionApi;
    }

    @Override
    public void onReceive(EventType eventType, JSONObject data) {
        if (eventType.equals(EventType.MESSAGES_UPSERT)) {
            Jid senderJid = new Jid(data.getJSONObject("key").getString("remoteJid"));

            String senderName = data.getString("pushName");
            String content = data.getJSONObject("message").getString("conversation");
            assignGPTModel(senderName, content, senderJid);
        }
    }

    private void assignGPTModel(String sender, String content, Jid identifier) {
        STEMApp.LOGGER.INFO("Receive Whatsapp input for AI model");
        STEMApp.getInstance().getScheduler().runTask(MirraPlugin.mirraPlugin, () -> {
            evolutionApi.SetOnlineOffline(true);
            evolutionApi.sendTypingPresence(identifier, 3000);
        });
        UserToken userToken = MirraPlugin.mirraPlugin.getIdentityManager().getOrCreateUserToken(identifier.toString(), TokenSource.WHATSAPP);
        IdentityUser identityUser = MirraPlugin.mirraPlugin.getIdentityManager().getIdentityUserByToken(userToken);
        if (identityUser instanceof IdentityGuest) {
            ((IdentityGuest) identityUser).setGuestName(sender);
        }
        List<String> input = MirraPlugin.mirraPlugin.getAiManager().getDefaultModel().buildMessageBlock(identityUser.getIdentityName(), content, userToken.getSource().name());
        String chatMessage = MirraPlugin.mirraPlugin.getAiManager().getDefaultModel().requestChatCompletion(input, userToken, sender);
        STEMApp.LOGGER.INFO("Response fom AI model received.");
        STEMApp.LOGGER.CORE(chatMessage);
        STEMApp.LOGGER.CORE("Jid text:" + identifier);
        evolutionApi.sendTextMessage(identifier, chatMessage);
        evolutionApi.SetOnlineOffline(false);
    }
}

