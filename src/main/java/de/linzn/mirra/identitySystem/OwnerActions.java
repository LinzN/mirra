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

import de.linzn.mirra.MirraPlugin;
import de.linzn.mirra.agent.ChatContext;
import de.linzn.mirra.agent.MirraAgent;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Changes of user profiles that only an owner may decide: deleting profiles, assigning or removing channels and
 * merging profiles. Such a change is first registered as pending action; it runs only when an owner confirms it
 * with a later message (never in the same turn and never in an automatic turn). Requests of administrators are
 * sent to the owners.
 */
public class OwnerActions {

    private static final Duration EXPIRY = Duration.ofHours(24);

    /**
     * Something that changes the database once confirmed.
     */
    public interface Action {
        String run() throws Exception;
    }

    public record Pending(int id, String description, String requestedBy, String turn, Instant created, Action action) {
    }

    private final MirraPlugin plugin;
    private final Map<Integer, Pending> pending = new ConcurrentHashMap<>();
    private final AtomicInteger ids = new AtomicInteger();

    public OwnerActions(MirraPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Register an action that needs the confirmation of an owner and ask the owners.
     *
     * @return the pending action
     */
    public Pending request(ChatContext requester, String description, Action action) {
        this.expire();
        List<IdentityUser> owners = this.owners();
        if (owners.isEmpty()) {
            throw new IllegalStateException("There is no owner (permission OWNER) who could confirm this");
        }
        Pending entry = new Pending(this.ids.incrementAndGet(), description, requester.name(), MirraAgent.currentTurn().id(),
                Instant.now(), action);
        this.pending.put(entry.id(), entry);
        boolean requesterIsOwner = isOwner(requester.identityUser());
        if (!requesterIsOwner) {
            String text = "🔐 Bestätigung nötig (Aktion #" + entry.id() + ")\n" + requester.name() + " möchte: " + description
                    + "\nSoll ich das machen? Antworte z.B. \"ja, Aktion " + entry.id() + "\" oder \"nein\".";
            for (IdentityUser owner : owners) {
                this.plugin.getMessenger().tokenOf(owner, null).ifPresent(token -> {
                    try {
                        this.plugin.getMessenger().sendText(token, text);
                        this.plugin.getAgent().recordOutgoing(token.getName(), "[Confirmation request sent by Mirra] " + text);
                    } catch (Exception e) {
                        this.plugin.getPluginLogger().warning("Could not ask owner " + owner.getIdentityName() + ": " + e.getMessage());
                    }
                });
            }
        }
        this.plugin.getPluginLogger().info("Owner action #" + entry.id() + " requested by " + requester.name() + ": " + description);
        return entry;
    }

    /**
     * Confirm or reject a pending action.
     *
     * @return result text of the action
     */
    public String decide(ChatContext chat, int id, boolean approve) throws Exception {
        if (!isOwner(chat.identityUser())) {
            throw new IllegalArgumentException("Only an owner can confirm this");
        }
        MirraAgent.Turn turn = MirraAgent.currentTurn();
        if (turn.automatic()) {
            throw new IllegalArgumentException("Owner actions cannot be confirmed in automatic turns");
        }
        this.expire();
        Pending entry = this.pending.get(id);
        if (entry == null) {
            throw new IllegalArgumentException("No pending action #" + id + " (expired or already decided)");
        }
        if (entry.turn().equals(turn.id())) {
            throw new IllegalArgumentException("The owner has to confirm with a new message. Ask: \"" + entry.description()
                    + "\" - yes or no?");
        }
        this.pending.remove(id);
        if (!approve) {
            this.plugin.getPluginLogger().info("Owner action #" + id + " rejected by " + chat.name());
            return "Rejected: " + entry.description();
        }
        String result = entry.action().run();
        this.plugin.getPluginLogger().info("Owner action #" + id + " confirmed by " + chat.name() + ": " + entry.description());
        return result;
    }

    public List<Pending> list() {
        this.expire();
        List<Pending> list = new ArrayList<>(this.pending.values());
        list.sort(Comparator.comparingInt(Pending::id));
        return list;
    }

    public static boolean isOwner(IdentityUser user) {
        return user != null && !user.isGuest() && user.getPermissions().contains(AiPermissions.OWNER);
    }

    private List<IdentityUser> owners() {
        return this.plugin.getIdentityManager().getIdentities().stream().filter(OwnerActions::isOwner).toList();
    }

    private void expire() {
        Instant limit = Instant.now().minus(EXPIRY);
        this.pending.values().removeIf(entry -> entry.created().isBefore(limit));
    }
}
