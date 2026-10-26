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

package de.linzn.mirra.messaging;

import de.linzn.evolutionApiJava.EvolutionApi;
import de.linzn.evolutionApiJava.api.JidClient;
import de.linzn.mirra.MirraConfig;
import de.linzn.mirra.MirraPlugin;
import de.linzn.mirra.agent.openai.OpenAIClient;
import de.linzn.mirra.identitySystem.IdentityUser;
import de.linzn.mirra.identitySystem.TokenSource;
import de.linzn.mirra.identitySystem.UserToken;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.PrivateChannel;
import net.dv8tion.jda.api.utils.FileUpload;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Sends text, files and voice messages to Discord users and WhatsApp chats.
 * All methods block until the message was handed over and throw {@link MessengerException} on failure.
 */
public class ChatMessenger {

    /* bots may upload 10 MB per message on servers without boost */
    private static final long DISCORD_MAX_BYTES = 10L * 1024 * 1024;
    private static final long WHATSAPP_MAX_BYTES = 64L * 1024 * 1024;

    private final MirraPlugin plugin;
    private final MirraConfig config;
    private final OpenAIClient client;

    public ChatMessenger(MirraPlugin plugin, MirraConfig config, OpenAIClient client) {
        this.plugin = plugin;
        this.config = config;
        this.client = client;
    }

    /**
     * @return true if messages can be sent to this token (Discord or WhatsApp and the service is running)
     */
    public boolean canSend(UserToken token) {
        if (token == null) {
            return false;
        }
        return switch (token.getSource()) {
            case DISCORD -> this.plugin.getDiscordManager() != null && this.plugin.getDiscordManager().getJda() != null;
            case WHATSAPP -> this.plugin.getWhatsappManager() != null && this.plugin.getWhatsappManager().isEnabled();
            default -> false;
        };
    }

    /**
     * Choose the token to reach an identity.
     *
     * @param channel discord, whatsapp or null for the first available
     */
    public Optional<UserToken> tokenOf(IdentityUser identity, String channel) {
        List<UserToken> tokens = identity.getUserTokens();
        if (channel != null && !channel.isBlank()) {
            TokenSource wanted = TokenSource.valueOf(channel.trim().toUpperCase(Locale.ROOT));
            return tokens.stream().filter(token -> token.getSource() == wanted && this.canSend(token)).findFirst();
        }
        /* WhatsApp first: it is the channel people read on their phone */
        return tokens.stream().filter(token -> token.getSource() == TokenSource.WHATSAPP && this.canSend(token)).findFirst()
                .or(() -> tokens.stream().filter(this::canSend).findFirst());
    }

    public void sendText(UserToken token, String text) throws MessengerException {
        this.requireSendable(token);
        try {
            if (token.getSource() == TokenSource.DISCORD) {
                this.plugin.getDiscordManager().sendMessage(this.discordChannel(token), text);
            } else {
                this.plugin.getWhatsappManager().getEvolutionApi().getWebApiProvider().sendTextMessage(this.jid(token), text);
            }
        } catch (MessengerException e) {
            throw e;
        } catch (Exception e) {
            throw new MessengerException("Sending the message failed: " + e.getMessage());
        }
    }

    public void sendFile(UserToken token, OutgoingFile file, String caption) throws MessengerException {
        this.requireSendable(token);
        try {
            if (token.getSource() == TokenSource.DISCORD) {
                if (file.data().length > DISCORD_MAX_BYTES) {
                    throw new MessengerException("File is larger than 10 MB, Discord does not accept it");
                }
                PrivateChannel channel = this.discordChannel(token);
                FileUpload upload = FileUpload.fromData(file.data(), file.fileName());
                if (caption != null && !caption.isBlank()) {
                    channel.sendMessage(caption.length() > 1900 ? caption.substring(0, 1900) : caption).addFiles(upload).complete();
                } else {
                    channel.sendFiles(upload).complete();
                }
            } else {
                if (file.data().length > WHATSAPP_MAX_BYTES) {
                    throw new MessengerException("File is larger than 64 MB");
                }
                this.plugin.getWhatsappManager().getEvolutionApi().getWebApiProvider()
                        .sendMedia(this.jid(token), file.category(), file.mimeType(), file.fileName(), caption, file.data());
            }
        } catch (MessengerException e) {
            throw e;
        } catch (Exception e) {
            throw new MessengerException("Sending the file failed: " + e.getMessage());
        }
    }

    /**
     * Speak a text and send it as voice message (WhatsApp) or audio file (Discord).
     */
    public void sendVoice(UserToken token, String text) throws MessengerException {
        this.requireSendable(token);
        if (!this.config.voiceEnabled) {
            throw new MessengerException("Voice messages are disabled");
        }
        byte[] audio;
        try {
            audio = this.client.speech(this.config.voiceModel, this.config.voiceName, this.config.voiceInstructions,
                    text.length() > 4000 ? text.substring(0, 4000) : text, "opus");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MessengerException("Interrupted");
        } catch (Exception e) {
            throw new MessengerException("Speech synthesis failed: " + e.getMessage());
        }
        try {
            if (token.getSource() == TokenSource.DISCORD) {
                this.sendFile(token, new OutgoingFile("Mirra.ogg", "audio/ogg", audio), null);
            } else {
                this.plugin.getWhatsappManager().getEvolutionApi().getWebApiProvider().sendVoiceMessage(this.jid(token), audio);
            }
        } catch (MessengerException e) {
            throw e;
        } catch (Exception e) {
            throw new MessengerException("Sending the voice message failed: " + e.getMessage());
        }
    }

    private void requireSendable(UserToken token) throws MessengerException {
        if (!this.canSend(token)) {
            throw new MessengerException("Messages can only be sent to Discord or WhatsApp chats, and the service must be running");
        }
    }

    private PrivateChannel discordChannel(UserToken token) throws MessengerException {
        JDA jda = this.plugin.getDiscordManager().getJda();
        if (jda == null) {
            throw new MessengerException("Discord is not connected");
        }
        return jda.retrieveUserById(token.getName()).complete().openPrivateChannel().complete();
    }

    private JidClient jid(UserToken token) throws Exception {
        return EvolutionApi.getClientCache().requestOf(token.getName());
    }
}
