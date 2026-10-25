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
import de.linzn.evolutionApiJava.api.JidClient;
import de.linzn.evolutionApiJava.api.exceptions.InvalidJidClient;
import de.linzn.mirra.MirraPlugin;
import de.linzn.mirra.whatsapp.listener.OnNewChatMessageListener;

public class WhatsappManager {

    private final MirraPlugin plugin;
    private final EvolutionApi evolutionApi;
    public final String defaultJID;
    private volatile boolean enabled;

    public WhatsappManager(MirraPlugin plugin) {
        this.plugin = plugin;
        this.defaultJID = plugin.getDefaultConfig().getString("whatsapp.defaultJID", "xxxx");
        String evolutionApiHostname = plugin.getDefaultConfig().getString("evolutionApi.hostname", "http://localhost:8080");
        String evolutionApiInstance = plugin.getDefaultConfig().getString("evolutionApi.instance", "MirraAPI");
        String evolutionApiApiKey = plugin.getDefaultConfig().getString("evolutionApi.apiKey", "xxxxxxxxxxxxxxxxxxxxxx");
        String rabbitMQHostname = plugin.getDefaultConfig().getString("rabbitMQ.hostname", "127.0.0.1");
        String rabbitMQUsername = plugin.getDefaultConfig().getString("rabbitMQ.username", "mirra");
        String rabbitMQPassword = plugin.getDefaultConfig().getString("rabbitMQ.password", "password");
        String rabbitMQVirtualHost = plugin.getDefaultConfig().getString("rabbitMQ.virtualHost", "/");
        plugin.getDefaultConfig().save();

        this.evolutionApi = new EvolutionApi(evolutionApiHostname, evolutionApiApiKey, evolutionApiInstance);
        this.evolutionApi.setRabbitMQ(rabbitMQHostname, rabbitMQUsername, rabbitMQPassword, rabbitMQVirtualHost);
        this.evolutionApi.registerLogger(new EvolutionConvertLogger(plugin));
        this.evolutionApi.getEventHandler().register(new OnNewChatMessageListener(plugin, this.evolutionApi));
        try {
            this.evolutionApi.enable();
            this.enabled = true;
        } catch (Exception e) {
            plugin.getPluginLogger().error("Unable to connect to evolution api / RabbitMQ, WhatsApp is disabled", e);
        }
    }

    public EvolutionApi getEvolutionApi() {
        return evolutionApi;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Sends a text message, logs instead of throwing on failure.
     */
    public void sendTextMessage(String jid, String message) {
        if (!this.enabled || jid == null || message == null || message.isBlank()) {
            return;
        }
        try {
            JidClient jidClient = EvolutionApi.getClientCache().requestOf(jid);
            this.evolutionApi.getWebApiProvider().sendTextMessage(jidClient, message);
        } catch (InvalidJidClient e) {
            this.plugin.getPluginLogger().warning("Invalid WhatsApp receiver " + jid + ": " + e.getMessage());
        } catch (Exception e) {
            this.plugin.getPluginLogger().error("Unable to send WhatsApp message", e);
        }
    }
}
