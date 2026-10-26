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

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;

public class IdentityUser {

    private final String identityName;
    private final List<UserToken> userTokens;
    private final Set<AiPermissions> aiPermissionsList;

    public IdentityUser(String identityName) {
        this.identityName = identityName;
        this.userTokens = new CopyOnWriteArrayList<>();
        this.aiPermissionsList = new CopyOnWriteArraySet<>();
    }

    public void addPermission(AiPermissions aiPermissions) {
        this.aiPermissionsList.add(aiPermissions);
    }

    public boolean hasPermission(AiPermissions aiPermissions) {
        if (this.aiPermissionsList.contains(aiPermissions)) {
            return true;
        }
        /* administrators may read everything they may change */
        return aiPermissions == AiPermissions.STEM_READ && this.aiPermissionsList.contains(AiPermissions.STEM_ADMIN);
    }

    public boolean hasUserToken(UserToken userToken) {
        return this.userTokens.contains(userToken);
    }

    public void assignUserToken(UserToken userToken) {
        this.userTokens.add(userToken);
    }

    public String getIdentityName() {
        return identityName;
    }

    public List<UserToken> getUserTokens() {
        return this.userTokens;
    }
}
