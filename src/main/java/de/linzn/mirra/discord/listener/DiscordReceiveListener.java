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

package de.linzn.mirra.discord.listener;

import de.linzn.mirra.MirraPlugin;
import de.linzn.mirra.agent.ChatContext;
import de.linzn.mirra.discord.DiscordManager;
import de.linzn.mirra.identitySystem.TokenSource;
import de.linzn.mirra.identitySystem.UserToken;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

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
        if (inputData == null || inputData.isBlank()) {
            return;
        }
        /* the agent may run several tools, never block the JDA event thread */
        this.plugin.getStemApi().scheduler().runAsync(() -> {
            try {
                this.answer(user, inputData);
            } catch (Exception e) {
                this.plugin.getPluginLogger().error("Discord AI request failed", e);
            }
        });
    }

    private void answer(User user, String content) {
        this.plugin.getPluginLogger().info("Discord message from " + user.getName());
        UserToken userToken = this.plugin.getIdentityManager().getOrCreateUserToken(user.getId(), TokenSource.DISCORD);
        ChatContext chat = this.plugin.chatContext(userToken, user.getName());
        String answer = this.plugin.getAgent().chat(chat, content);
        this.plugin.getPluginLogger().debug("Answer to " + user.getName() + ": " + answer);
        if (answer != null && !answer.isBlank()) {
            this.discordManager.sendPrivateMessage(user.getId(), answer);
        }
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (event.getName().equals("ping")) {
            event.reply("pong").queue(); // reply immediately
        }
    }
}
