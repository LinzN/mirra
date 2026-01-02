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

import de.linzn.stem.STEMApp;

import java.util.ArrayList;
import java.util.List;

public class IdentityUser {

    private final String identityName;
    private final List<UserToken> userTokens;
    private final List<AiPermissions> aiPermissionsList;

    public IdentityUser(String identityName) {
        this.identityName = identityName;
        this.userTokens = new ArrayList<>();
        this.aiPermissionsList = new ArrayList<>();
    }

    public void addPermission(AiPermissions aiPermissions) {
        STEMApp.LOGGER.CONFIG("ADD AIPermissions " + aiPermissions.name() + " to " + this.getIdentityName());
        this.aiPermissionsList.add(aiPermissions);
    }

    public boolean hasPermission(AiPermissions aiPermissions) {
        return this.aiPermissionsList.contains(aiPermissions);
    }

    public boolean hasUserToken(UserToken userToken) {
        return this.userTokens.contains(userToken);
    }

    public void assignUserToken(UserToken userToken) {
        STEMApp.LOGGER.CONFIG("ADD IdentityToken " + userToken.getName() + " to " + this.getIdentityName());
        this.userTokens.add(userToken);
    }

    public String getIdentityName() {
        return identityName;
    }

    List<UserToken> getUserTokens() {
        return this.userTokens;
    }
}
