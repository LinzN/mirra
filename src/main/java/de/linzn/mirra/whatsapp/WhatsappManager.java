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

import de.linzn.evolutionApiJava.EvolutionApi;
import de.linzn.evolutionApiJava.poolMQ.EventType;
import de.linzn.mirra.MirraPlugin;
import de.linzn.mirra.whatsapp.listener.OnNewChatMessageListener;
import de.linzn.stem.STEMApp;

import java.io.IOException;
import java.util.concurrent.TimeoutException;


public class WhatsappManager {

    private final EvolutionApi evolutionApi;
    public String defaultJID;

    public WhatsappManager() {
        this.defaultJID = MirraPlugin.mirraPlugin.getDefaultConfig().getString("whatsapp.defaultJID", "xxxx");
        String evolutionApiHostname = MirraPlugin.mirraPlugin.getDefaultConfig().getString("evolutionApi.hostname", "http://localhost:8080");
        String evolutionApiInstance = MirraPlugin.mirraPlugin.getDefaultConfig().getString("evolutionApi.instance", "MirraAPI");
        String evolutionApiApiKey = MirraPlugin.mirraPlugin.getDefaultConfig().getString("evolutionApi.apiKey", "xxxxxxxxxxxxxxxxxxxxxx");
        String rabbitMQHostname = MirraPlugin.mirraPlugin.getDefaultConfig().getString("rabbitMQ.hostname", "127.0.0.1");
        String rabbitMQUsername = MirraPlugin.mirraPlugin.getDefaultConfig().getString("rabbitMQ.username", "mirra");
        String rabbitMQPassword = MirraPlugin.mirraPlugin.getDefaultConfig().getString("rabbitMQ.password", "password");
        String rabbitMQVirtualHost = MirraPlugin.mirraPlugin.getDefaultConfig().getString("rabbitMQ.virtualHost", "/");
        MirraPlugin.mirraPlugin.getDefaultConfig().save();

        this.evolutionApi = new EvolutionApi(evolutionApiHostname, evolutionApiApiKey, evolutionApiInstance, rabbitMQHostname, rabbitMQUsername, rabbitMQPassword, rabbitMQVirtualHost);
        this.evolutionApi.registerListener(EventType.MESSAGES_UPSERT, new OnNewChatMessageListener(this.evolutionApi));
        try {
            this.evolutionApi.enable();
        } catch (IOException | TimeoutException e) {
            STEMApp.LOGGER.ERROR(e);
        }
    }


    public EvolutionApi getEvolutionApi() {
        return evolutionApi;
    }
}
