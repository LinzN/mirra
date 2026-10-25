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

package de.linzn.mirra.discord.listener;

import de.linzn.mirra.MirraPlugin;
import de.linzn.mirra.discord.DiscordManager;
import de.linzn.mirra.identitySystem.IdentityGuest;
import de.linzn.mirra.identitySystem.IdentityUser;
import de.linzn.mirra.identitySystem.TokenSource;
import de.linzn.mirra.identitySystem.UserToken;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.util.List;

public class DiscordReceiveListener extends ListenerAdapter {

    private final MirraPlugin plugin;
    private final DiscordManager discordManager;

    public DiscordReceiveListener(MirraPlugin plugin, DiscordManager discordManager) {
        this.plugin = plugin;
        this.discordManager = discordManager;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (!event.isFromType(ChannelType.PRIVATE) || event.getAuthor().isBot()) {
            return;
        }
        String inputData = event.getMessage().getContentDisplay();
        User user = event.getAuthor();
        /* the AI request takes seconds, never block the JDA event thread */
        this.plugin.getStemApi().scheduler().runAsync(() -> {
            try {
                this.assignGPTModel(user.getName(), inputData, user);
            } catch (Exception e) {
                this.plugin.getPluginLogger().error("Discord AI request failed", e);
            }
        });
    }

    private void assignGPTModel(String sender, String content, User user) {
        this.plugin.getPluginLogger().info("Receive Discord input for AI model");
        UserToken userToken = this.plugin.getIdentityManager().getOrCreateUserToken(user.getId(), TokenSource.DISCORD);
        IdentityUser identityUser = this.plugin.getIdentityManager().getIdentityUserByToken(userToken);
        if (identityUser instanceof IdentityGuest identityGuest) {
            identityGuest.setGuestName(sender);
        }
        List<String> input = this.plugin.getAiManager().getDefaultModel().buildMessageBlock(identityUser.getIdentityName(), content, userToken.getSource().name());
        String chatMessage = this.plugin.getAiManager().getDefaultModel().requestChatCompletion(input, userToken, sender);
        this.plugin.getPluginLogger().info("Response fom AI model received.");
        this.plugin.getPluginLogger().debug(chatMessage);
        if (chatMessage != null && !chatMessage.isBlank()) {
            this.discordManager.sendPrivateMessage(user.getId(), chatMessage);
        }
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (event.getName().equals("ping")) {
            event.reply("pong").queue(); // reply immediately
        }
    }
}
