/*
 * Copyright (c) 2025 MirraNET, Niklas Linz. All rights reserved.
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

public class IdentityGuest extends IdentityUser {

    private String guestName;

    public IdentityGuest(UserToken userToken) {
        super("Guest");
        this.assignUserToken(userToken);
        this.addPermission(AiPermissions.STATUS_STEM);
    }

    @Override
    public String getIdentityName() {
        return guestName;
    }

    public void setGuestName(String guestName) {
        this.guestName = guestName;
    }
}
