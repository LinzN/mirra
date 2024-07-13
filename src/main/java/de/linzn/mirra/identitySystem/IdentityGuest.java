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
