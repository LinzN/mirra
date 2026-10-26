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
 * Anonymous profile for senders that have no stored profile (e.g. when guests are not created automatically).
 */
public class IdentityGuest extends IdentityUser {

    private volatile String guestName;

    public IdentityGuest(UserToken userToken) {
        super(-1, "Guest", true);
        if (userToken != null) {
            this.assignUserToken(userToken);
        }
    }

    @Override
    public String getIdentityName() {
        return guestName != null ? guestName : super.getIdentityName();
    }

    public void setGuestName(String guestName) {
        this.guestName = guestName;
    }
}
