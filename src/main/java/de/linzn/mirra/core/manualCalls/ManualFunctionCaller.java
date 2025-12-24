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

package de.linzn.mirra.core.manualCalls;

import de.linzn.mirra.MirraPlugin;
import de.linzn.openJL.pairs.Pair;
import de.stem.stemSystem.STEMSystemApp;

import java.util.concurrent.TimeUnit;

public abstract class ManualFunctionCaller {

    public ManualFunctionCaller() {
        Pair<Integer, TimeUnit> initialCron = this.delayedStart();
        if (initialCron != null) {
            STEMSystemApp.LOGGER.CONFIG("Register initial manual function caller for " + this.getClass().getName());
            STEMSystemApp.getInstance().getScheduler().runTaskLater(MirraPlugin.mirraPlugin, this::call, initialCron.getKey(), initialCron.getValue());
        }

        String repeatCron = this.repeatCronString();
        if (repeatCron != null) {
            STEMSystemApp.LOGGER.CONFIG("Register cron manual function caller for " + this.getClass().getName());
            STEMSystemApp.getInstance().getScheduler().runAsCronTask(MirraPlugin.mirraPlugin, this::call, repeatCron);
        }
    }

    public abstract void call();

    public abstract Pair<Integer, TimeUnit> delayedStart();

    public abstract String repeatCronString();

}
