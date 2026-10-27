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

package de.linzn.mirra.identitySystem;

import de.linzn.mirra.MirraConfig;
import de.linzn.mirra.MirraPlugin;
import de.linzn.mirra.agent.ChatContext;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides whether a sender may chat with Mirra. A new guest gets a short "request pending" message and the
 * approver (by default the default user) is asked whether the person may write. Until the approver decides,
 * further messages of the guest are not answered by Mirra. After the approval the first message is answered.
 */
public class AccessControl {

    /* the pending info is repeated at most this often */
    private static final Duration PENDING_REPEAT = Duration.ofHours(6);
    private static final String FIRST_MESSAGE_KEY = "access_request_message";

    private final MirraPlugin plugin;
    private final MirraConfig config;
    private final Map<Integer, Instant> pendingInfo = new ConcurrentHashMap<>();

    public AccessControl(MirraPlugin plugin, MirraConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    /**
     * Check an incoming message. Handles pending and denied senders completely.
     *
     * @param message text of the message (stored for a new request, so it can be answered after the approval)
     * @return true if Mirra may answer the message
     */
    public boolean allow(ChatContext chat, String message) {
        if (!this.config.requireApproval || chat.isInternal()) {
            return true;
        }
        IdentityUser user = chat.identityUser();
        if (user == null || user.getId() < 0) {
            /* no profile, nobody can approve it */
            this.plugin.getPluginLogger().info("Message of " + chat.userToken() + " ignored, no profile");
            return false;
        }
        switch (user.getAccess()) {
            case APPROVED -> {
                return true;
            }
            case DENIED -> {
                this.plugin.getPluginLogger().debug("Message of denied user " + user.getIdentityName() + " ignored");
                return false;
            }
            default -> {
                boolean first = !user.getAttributes().containsKey(FIRST_MESSAGE_KEY);
                if (first) {
                    this.request(chat, user, message);
                } else {
                    Instant last = this.pendingInfo.get(user.getId());
                    if (last == null || Duration.between(last, Instant.now()).compareTo(PENDING_REPEAT) > 0) {
                        this.sendTo(chat.userToken(), this.config.pendingMessage);
                        this.pendingInfo.put(user.getId(), Instant.now());
                    }
                }
                return false;
            }
        }
    }

    private void request(ChatContext chat, IdentityUser user, String message) {
        try {
            this.plugin.getIdentityManager().setProfileValue(user, FIRST_MESSAGE_KEY,
                    message == null || message.isBlank() ? "(no text)" : message, "auto");
        } catch (SQLException e) {
            this.plugin.getPluginLogger().warning("Could not store the access request of " + user.getIdentityName() + ": " + e.getMessage());
        }
        this.sendTo(chat.userToken(), this.config.pendingMessage);
        this.pendingInfo.put(user.getId(), Instant.now());

        String text = "🔔 Neue Kontaktanfrage an Mirra\n"
                + describe(chat.userToken()) + "\n"
                + "Profil: " + user.getIdentityName() + "\n"
                + "Erste Nachricht: " + shorten(message, 300) + "\n\n"
                + "Darf die Person mit Mirra schreiben? Antworte z.B. \"ja, " + user.getIdentityName() + " darf schreiben\", \"nein\" "
                + "oder \"das ist der Account von <Name>\", wenn er zu einem bestehenden Nutzer gehört.";
        Optional<IdentityUser> approver = this.plugin.getIdentityManager().findIdentity(this.config.approverName);
        Optional<UserToken> target = approver.flatMap(identity -> this.plugin.getMessenger().tokenOf(identity, null));
        if (target.isEmpty()) {
            this.plugin.getPluginLogger().warning("Access request of " + user.getIdentityName() + " could not be sent, approver '"
                    + this.config.approverName + "' not reachable");
            return;
        }
        this.sendTo(target.get(), text);
        /* in the chat history of the approver, so a short "ja" is understood */
        this.plugin.getAgent().recordOutgoing(target.get().getName(), text);
        this.plugin.getPluginLogger().info("Access request of " + user.getIdentityName() + " sent to " + approver.get().getIdentityName());
    }

    /**
     * Approve or deny a person. On approval the person is informed and the first message is answered.
     */
    public void decide(IdentityUser user, boolean allowed, String decidedBy) throws SQLException {
        this.plugin.getIdentityManager().setAccess(user, allowed ? IdentityUser.Access.APPROVED : IdentityUser.Access.DENIED);
        this.pendingInfo.remove(user.getId());
        this.plugin.getPluginLogger().info(decidedBy + (allowed ? " approved " : " denied ") + user.getIdentityName());
        String firstMessage = user.getAttributes().get(FIRST_MESSAGE_KEY);
        this.plugin.getIdentityManager().setProfileValue(user, FIRST_MESSAGE_KEY, null, "auto");
        List<UserToken> channels = List.copyOf(user.getUserTokens());
        if (channels.isEmpty()) {
            return;
        }
        UserToken channel = channels.get(0);
        if (!allowed) {
            if (!this.config.deniedMessage.isBlank()) {
                this.sendTo(channel, this.config.deniedMessage);
            }
            return;
        }
        this.sendTo(channel, this.config.approvedMessage);
        if (firstMessage != null && !firstMessage.equals("(no text)")) {
            /* answer the message the person wrote before the approval */
            this.plugin.getStemApi().scheduler().runAsync(() -> {
                ChatContext chat = this.plugin.chatContext(channel, channel.getDisplayName() == null ? user.getIdentityName() : channel.getDisplayName());
                String answer = this.plugin.getAgent().chat(chat, firstMessage);
                this.plugin.deliverAnswer(chat, answer, false);
            });
        }
    }

    /**
     * @return profiles waiting for a decision
     */
    public List<IdentityUser> pending() {
        return this.plugin.getIdentityManager().getIdentities().stream()
                .filter(user -> user.getAccess() == IdentityUser.Access.PENDING).toList();
    }

    /**
     * Discord: name and id; WhatsApp: number and name.
     */
    public static String describe(UserToken token) {
        String name = token.getDisplayName() == null || token.getDisplayName().isBlank() ? null : token.getDisplayName();
        if (token.getSource() == TokenSource.DISCORD) {
            return "Discord: " + (name == null ? "(unbekannter Name)" : name) + " (ID " + token.getName() + ")";
        }
        String address = token.getName();
        String number = address.endsWith("@s.whatsapp.net") ? "+" + address.substring(0, address.indexOf('@')) : address;
        return "WhatsApp: " + number + (name == null ? "" : " (" + name + ")");
    }

    private void sendTo(UserToken token, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        try {
            this.plugin.getMessenger().sendText(token, text);
        } catch (Exception e) {
            this.plugin.getPluginLogger().warning("Could not send access message to " + token + ": " + e.getMessage());
        }
    }

    private static String shorten(String text, int max) {
        if (text == null || text.isBlank()) {
            return "(keine Text-Nachricht)";
        }
        return text.length() > max ? text.substring(0, max) + "…" : text;
    }
}
