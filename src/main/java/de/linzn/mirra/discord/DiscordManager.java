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

package de.linzn.mirra.discord;

import de.linzn.mirra.MirraPlugin;
import de.linzn.mirra.discord.listener.DiscordReceiveListener;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.GatewayIntent;

import java.util.ArrayList;
import java.util.List;

public class DiscordManager {

    /* discord allows 2000 characters per message */
    private static final int MAX_MESSAGE_LENGTH = 1900;

    private final MirraPlugin plugin;
    public final String defaultUID;
    private volatile JDA jda;
    private volatile boolean shutdown;

    public DiscordManager(MirraPlugin plugin) {
        this.plugin = plugin;
        String token = plugin.getDefaultConfig().getString("discord.token", "xxxx");
        this.defaultUID = plugin.getDefaultConfig().getString("discord.defaultUID", "xxxx");
        plugin.getDefaultConfig().save();
        if (token == null || token.isBlank() || token.equals("xxxx")) {
            plugin.getPluginLogger().warning("No discord.token configured, Discord is not started");
            return;
        }
        plugin.getStemApi().scheduler().runAsync(() -> this.login(token));
    }

    private void login(String token) {
        JDA newJda;
        try {
            JDABuilder jdaBuilder = JDABuilder.createLight(token);
            jdaBuilder.setAutoReconnect(true);
            jdaBuilder.enableIntents(GatewayIntent.MESSAGE_CONTENT);
            jdaBuilder.addEventListeners(new DiscordReceiveListener(this.plugin, this));
            newJda = jdaBuilder.build();
        } catch (Exception e) {
            this.plugin.getPluginLogger().error("Login Discord API failed!", e);
            return;
        }
        if (this.shutdown) {
            newJda.shutdownNow();
            return;
        }
        this.jda = newJda;
        try {
            newJda.awaitReady();
            this.plugin.getPluginLogger().info("Login Discord API success!");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            this.plugin.getPluginLogger().error("Login Discord API interrupted!");
            return;
        }
        newJda.getPresence().setActivity(Activity.playing("Working on MirraNET"));
        newJda.updateCommands().addCommands(
                Commands.slash("ping", "Pong")
        ).queue();
    }

    public void shutdown() {
        this.shutdown = true;
        JDA current = this.jda;
        this.jda = null;
        if (current != null) {
            current.shutdownNow();
        }
    }

    /**
     * @return the JDA instance or null while not logged in
     */
    public JDA getJda() {
        return jda;
    }

    /**
     * Sets the bot activity, ignored while not logged in.
     */
    public void setActivity(String status) {
        JDA current = this.jda;
        if (current != null) {
            current.getPresence().setActivity(Activity.playing(status));
        }
    }

    /**
     * Sends a private message, split into several messages if it is too long. Asynchronous.
     */
    public void sendPrivateMessage(String userId, String message) {
        JDA current = this.jda;
        if (current == null || userId == null || message == null || message.isEmpty()) {
            this.plugin.getPluginLogger().warning("Discord not ready, private message dropped");
            return;
        }
        current.retrieveUserById(userId).queue(
                user -> user.openPrivateChannel().queue(
                        channel -> this.sendMessage(channel, message),
                        error -> this.plugin.getPluginLogger().error("Unable to open private channel to " + userId, error)),
                error -> this.plugin.getPluginLogger().error("Unable to find discord user " + userId, error));
    }

    public void sendMessage(MessageChannel channel, String message) {
        for (String part : split(message)) {
            channel.sendMessage(part).queue(null, error -> this.plugin.getPluginLogger().error("Unable to send discord message", error));
        }
    }

    private static List<String> split(String message) {
        List<String> parts = new ArrayList<>();
        for (int start = 0; start < message.length(); start += MAX_MESSAGE_LENGTH) {
            parts.add(message.substring(start, Math.min(message.length(), start + MAX_MESSAGE_LENGTH)));
        }
        return parts;
    }
}
