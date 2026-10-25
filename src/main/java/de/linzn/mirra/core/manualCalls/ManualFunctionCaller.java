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

package de.linzn.mirra.core.manualCalls;

import de.linzn.openJL.pairs.Pair;
import de.linzn.stem.modules.pluginModule.STEMPlugin;

import java.util.concurrent.TimeUnit;

public abstract class ManualFunctionCaller {

    /**
     * Registers the delayed and the cron call of this caller. The tasks belong to the plugin and are
     * cancelled on disable.
     */
    public void schedule(STEMPlugin plugin) {
        Pair<Integer, TimeUnit> initialCron = this.delayedStart();
        if (initialCron != null) {
            plugin.getPluginLogger().debug("Register initial manual function caller for " + this.getClass().getName());
            plugin.getStemApi().scheduler().runLater(() -> this.safeCall(plugin), initialCron.getKey(), initialCron.getValue());
        }
        String repeatCron = this.repeatCronString();
        if (repeatCron != null) {
            plugin.getPluginLogger().debug("Register cron manual function caller for " + this.getClass().getName());
            plugin.getStemApi().scheduler().runCron(() -> this.safeCall(plugin), repeatCron);
        }
    }

    private void safeCall(STEMPlugin plugin) {
        try {
            this.call();
        } catch (Exception e) {
            plugin.getPluginLogger().error("Manual function call " + this.getClass().getSimpleName() + " failed", e);
        }
    }

    public abstract void call();

    public abstract Pair<Integer, TimeUnit> delayedStart();

    public abstract String repeatCronString();
}
