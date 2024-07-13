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
