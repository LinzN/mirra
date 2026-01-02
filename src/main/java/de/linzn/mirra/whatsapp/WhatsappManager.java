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

package de.linzn.mirra.whatsapp;

import com.github.auties00.cobalt.client.WhatsAppClient;
import de.linzn.mirra.MirraPlugin;
import de.linzn.stem.STEMApp;

import java.util.UUID;

public class WhatsappManager {

    public String defaultJID;
    public UUID sessionUUID;
    private WhatsAppClient whatsapp;


    public WhatsappManager() {
        this.defaultJID = MirraPlugin.mirraPlugin.getDefaultConfig().getString("whatsapp.defaultJID", "xxxx");
        this.sessionUUID = UUID.fromString(MirraPlugin.mirraPlugin.getDefaultConfig().getString("whatsapp.sessionUUID", "dd8d7aca-a6cf-468f-a4bf-51c3b8ae7c8a"));
        MirraPlugin.mirraPlugin.getDefaultConfig().save();
        /*
        try {
            this.whatsapp = WhatsAppClient.builder().webClient()
                    .loadLastOrCreateConnection()
                    .unregistered(WhatsAppClientVerificationHandler.Web.QrCode.toTerminal())
                    .addListener(new OnLoggedInListener())
                    .addListener(new OnDisconnectedListener())
                    .addListener(new OnNewChatMessageListener())
                    .connect(); // join removed???
            Thread.sleep(1000);
            STEMSystemApp.getInstance().getScheduler().runRepeatScheduler(MirraPlugin.mirraPlugin, this::registerReconnectHandler, 30, 30, TimeUnit.SECONDS);
        } catch (Exception e) {
            STEMSystemApp.LOGGER.ERROR(e);
        }*/
    }

    private void registerReconnectHandler() {
        if (!whatsapp.isConnected()) {
            whatsapp.reconnect();
            STEMApp.LOGGER.ERROR("Whatsapp is no more connected. Try to reconnect");
            whatsapp.changePresence(true);
        } else {
            STEMApp.LOGGER.DEBUG("Whatsapp still connected!");
            whatsapp.changePresence(true);
        }
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        whatsapp.changePresence(false);
    }

    public WhatsAppClient getWhatsapp() {
        return whatsapp;
    }
}
