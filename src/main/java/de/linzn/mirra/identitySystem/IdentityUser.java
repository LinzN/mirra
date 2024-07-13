package de.linzn.mirra.identitySystem;

import de.stem.stemSystem.STEMSystemApp;

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
        STEMSystemApp.LOGGER.CONFIG("ADD AIPermissions " + aiPermissions.name() + " to " + this.getIdentityName());
        this.aiPermissionsList.add(aiPermissions);
    }

    public boolean hasPermission(AiPermissions aiPermissions) {
        return this.aiPermissionsList.contains(aiPermissions);
    }

    public boolean hasUserToken(UserToken userToken) {
        return this.userTokens.contains(userToken);
    }

    public void assignUserToken(UserToken userToken) {
        STEMSystemApp.LOGGER.CONFIG("ADD IdentityToken " + userToken.getName() + " to " + this.getIdentityName());
        this.userTokens.add(userToken);
    }

    public String getIdentityName() {
        return identityName;
    }

    List<UserToken> getUserTokens() {
        return this.userTokens;
    }
}
