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

/**
 * A chat channel of a user: a Discord account or a WhatsApp chat. The name is the address on that channel
 * (Discord user id, WhatsApp JID). Chat history is kept per channel, everything else per user.
 */
public class UserToken {
    private final int id;
    private final String name;
    private final TokenSource source;
    private volatile int userId;
    private volatile String displayName;

    UserToken(int id, String name, TokenSource source) {
        this(id, name, source, -1, null);
    }

    UserToken(int id, String name, TokenSource source, int userId, String displayName) {
        this.id = id;
        this.name = name;
        this.source = source;
        this.userId = userId;
        this.displayName = displayName;
    }

    /**
     * A channel that is not stored, e.g. for STEM itself.
     */
    public static UserToken internal(String name) {
        return new UserToken(-1, name, TokenSource.INTERNAL);
    }

    /**
     * A stand-in channel, e.g. to check which tools a permission unlocks. Never stored.
     */
    public static UserToken probe(TokenSource source) {
        return new UserToken(0, "probe", source);
    }

    /**
     * @return the address on the channel (Discord user id, WhatsApp JID)
     */
    public String getName() {
        return name;
    }

    public TokenSource getSource() {
        return source;
    }

    /**
     * @return channel id in the database, -1 if not stored
     */
    public int getId() {
        return id;
    }

    /**
     * @return id of the user the channel belongs to, -1 if none
     */
    public int getUserId() {
        return userId;
    }

    void setUserId(int userId) {
        this.userId = userId;
    }

    /**
     * @return name shown on the channel (Discord name, WhatsApp push name), may be null
     */
    public String getDisplayName() {
        return displayName;
    }

    void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    @Override
    public String toString() {
        return this.source.name().toLowerCase() + ":" + this.name;
    }
}
