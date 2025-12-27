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

package de.linzn.mirra.events;

import de.linzn.mirra.core.reminder.MirraReminder;
import de.linzn.stem.modules.eventModule.StemEvent;

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
