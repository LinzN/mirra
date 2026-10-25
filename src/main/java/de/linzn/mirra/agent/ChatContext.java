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

package de.linzn.mirra.agent;

import de.linzn.mirra.identitySystem.AiPermissions;
import de.linzn.mirra.identitySystem.IdentityGuest;
import de.linzn.mirra.identitySystem.IdentityUser;
import de.linzn.mirra.identitySystem.TokenSource;
import de.linzn.mirra.identitySystem.UserToken;

import java.util.Locale;

/**
 * Who Mirra is talking to and where.
 *
 * @param userToken    token of the sender (Discord user id, WhatsApp JID, internal handler)
 * @param identityUser resolved identity, a guest for unknown senders
 * @param displayName  name shown in the chat
 */
public record ChatContext(UserToken userToken, IdentityUser identityUser, String displayName) {

    /**
     * @return key of the chat history; the same as before the agent rewrite, so old history is kept
     */
    public String chatKey() {
        return this.userToken.getName();
    }

    /**
     * @return key of the long term memory: one per known person (shared by Discord and WhatsApp), one per guest token
     */
    public String memoryOwner() {
        return memoryOwner(this.userToken, this.identityUser);
    }

    public static String memoryOwner(UserToken userToken, IdentityUser identityUser) {
        if (identityUser != null && !(identityUser instanceof IdentityGuest)) {
            return "identity:" + identityUser.getIdentityName().toLowerCase(Locale.ROOT);
        }
        return "token:" + userToken.getSource().name().toLowerCase(Locale.ROOT) + ":" + userToken.getName();
    }

    public TokenSource source() {
        return this.userToken.getSource();
    }

    public boolean isGuest() {
        return this.identityUser instanceof IdentityGuest;
    }

    public boolean isInternal() {
        return this.userToken.getSource() == TokenSource.INTERNAL;
    }

    public boolean has(AiPermissions permission) {
        return this.identityUser.hasPermission(permission);
    }

    /**
     * @return identity name of registered users, the chat name of guests
     */
    public String name() {
        if (this.isGuest()) {
            return this.displayName == null || this.displayName.isBlank() ? "Guest" : this.displayName;
        }
        String name = this.identityUser.getIdentityName();
        return name == null || name.isBlank() ? this.displayName : name;
    }
}
