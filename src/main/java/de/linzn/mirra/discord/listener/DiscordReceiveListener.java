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
import de.linzn.mirra.agent.attachment.Attachment;
import de.linzn.mirra.agent.attachment.AttachmentProcessor;
import de.linzn.mirra.discord.DiscordManager;
import de.linzn.mirra.identitySystem.TokenSource;
import de.linzn.mirra.identitySystem.UserToken;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.util.ArrayList;
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
        List<Message.Attachment> files = List.copyOf(event.getMessage().getAttachments());
        User user = event.getAuthor();
        if ((inputData == null || inputData.isBlank()) && files.isEmpty()) {
            return;
        }
        /* the agent may run several tools, never block the JDA event thread */
        this.plugin.getStemApi().scheduler().runAsync(() -> {
            try {
                this.answer(user, inputData == null ? "" : inputData, files);
            } catch (Exception e) {
                this.plugin.getPluginLogger().error("Discord AI request failed", e);
            }
        });
    }

    private void answer(User user, String content, List<Message.Attachment> files) {
        this.plugin.getPluginLogger().info("Discord message from " + user.getName() + (files.isEmpty() ? "" : " with " + files.size() + " file(s)"));
        UserToken userToken = this.plugin.getIdentityManager().getOrCreateUserToken(user.getId(), TokenSource.DISCORD);
        ChatContext chat = this.plugin.chatContext(userToken, user.getName());
        List<Attachment> attachments = this.download(files);
        String answer = this.plugin.getAgent().chat(chat, content, attachments);
        this.plugin.getPluginLogger().debug("Answer to " + user.getName() + ": " + answer);
        boolean voice = attachments.stream().anyMatch(a -> a.voice() || (a.mimeType() != null && a.mimeType().startsWith("audio/")));
        this.plugin.deliverAnswer(chat, answer, voice);
    }

    private List<Attachment> download(List<Message.Attachment> files) {
        List<Attachment> attachments = new ArrayList<>();
        AttachmentProcessor processor = this.plugin.getAttachmentProcessor();
        for (Message.Attachment file : files) {
            try {
                if (processor == null || file.getSize() > processor.maxBytes()) {
                    /* an empty attachment tells the model the file was too large */
                    attachments.add(new Attachment(file.getFileName(), file.getContentType(), new byte[0], false));
                    continue;
                }
                attachments.add(new Attachment(file.getFileName(), file.getContentType(), processor.download(file.getUrl()), false));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                this.plugin.getPluginLogger().warning("Could not download Discord attachment " + file.getFileName() + ": " + e.getMessage());
                attachments.add(new Attachment(file.getFileName(), file.getContentType(), new byte[0], false));
            }
        }
        return attachments;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (event.getName().equals("ping")) {
            event.reply("pong").queue(); // reply immediately
        }
    }
}
