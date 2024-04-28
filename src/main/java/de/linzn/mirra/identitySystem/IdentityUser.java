package de.linzn.mirra.identitySystem;

import de.stem.stemSystem.STEMSystemApp;

import java.util.ArrayList;
import java.util.List;

public class IdentityUser {

    private final String identityName;
    private final List<String> identityTokens;
    private final List<AiPermissions> aiPermissionsList;

    public IdentityUser(String identityName) {
        this.identityName = identityName;
        this.identityTokens = new ArrayList<>();
        this.aiPermissionsList = new ArrayList<>();
    }

    public void addPermission(AiPermissions aiPermissions) {
        STEMSystemApp.LOGGER.CONFIG("ADD AIPermissions " + aiPermissions.name() + " to " + this.getIdentityName());
        this.aiPermissionsList.add(aiPermissions);
    }

    public boolean hasPermission(AiPermissions aiPermissions) {
        return this.aiPermissionsList.contains(aiPermissions);
    }

    public boolean hasIdentityToken(String token) {
        return this.identityTokens.contains(token);
    }

    public void addIdentityToken(String token) {
        STEMSystemApp.LOGGER.CONFIG("ADD IdentityToken " + token + " to " + this.getIdentityName());
        this.identityTokens.add(token);
    }

    public String getIdentityName() {
        return identityName;
    }
}
