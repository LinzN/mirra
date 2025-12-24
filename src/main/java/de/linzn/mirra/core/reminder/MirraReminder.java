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

package de.linzn.mirra.core.reminder;

import de.linzn.mirra.identitySystem.IdentityUser;
import de.linzn.mirra.identitySystem.UserToken;

import java.util.Date;

public class MirraReminder {
    private final int id;
    private final IdentityUser identityUser;
    private final UserToken userToken;
    private final String content;
    private final Date reminderDate;

    public MirraReminder(int id, IdentityUser identityUser, UserToken userToken, String content, Date reminderDate) {
        this.id = id;
        this.identityUser = identityUser;
        this.userToken = userToken;
        this.content = content;
        this.reminderDate = reminderDate;
    }

    public int getId() {
        return id;
    }

    public Date getReminderDate() {
        return reminderDate;
    }

    public String getContent() {
        return content;
    }

    public IdentityUser getIdentityUser() {
        return this.identityUser;
    }

    public UserToken getUserToken() {
        return userToken;
    }
}
