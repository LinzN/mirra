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

public class UserToken {
    private final int id;
    private final String name;
    private final TokenSource source;

    UserToken(int id, String name, TokenSource source) {
        this.id = id;
        this.name = name;
        this.source = source;
    }

    public String getName() {
        return name;
    }

    public TokenSource getSource() {
        return source;
    }

    public int getId() {
        return id;
    }
}
