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
