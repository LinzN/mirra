package de.linzn.mirra.events;

import de.linzn.mirra.core.reminder.MirraReminder;
import de.stem.stemSystem.modules.eventModule.StemEvent;

public class MirraReminderEvent implements StemEvent {

    private final MirraReminder mirraReminder;

    public MirraReminderEvent(MirraReminder mirraReminder) {
        this.mirraReminder = mirraReminder;
    }

    @Override
    public boolean isCancelable() {
        return false;
    }

    public MirraReminder getMirraReminder() {
        return mirraReminder;
    }
}
