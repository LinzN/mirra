/*
 * Copyright (C) 2020. Niklas Linz - All Rights Reserved
 * You may use, distribute and modify this code under the
 * terms of the LGPLv3 license, which unfortunately won't be
 * written for another century.
 *
 * You should have received a copy of the LGPLv3 license with
 * this file. If not, please write to: niklas.linz@enigmar.de
 *
 */

package de.linzn.mirra;


import de.linzn.mirra.core.AIManager;
import de.linzn.mirra.core.reminder.ReminderEngine;
import de.linzn.mirra.discord.DiscordManager;
import de.linzn.mirra.identitySystem.IdentityManager;
import de.linzn.mirra.listener.MirraReminderListener;
import de.linzn.mirra.listener.StemEventListener;
import de.linzn.mirra.whatsapp.WhatsappManager;
import de.stem.stemSystem.STEMSystemApp;
import de.stem.stemSystem.modules.pluginModule.STEMPlugin;

public class MirraPlugin extends STEMPlugin {

    public static MirraPlugin mirraPlugin;
    private IdentityManager identityManager;
    private WhatsappManager whatsappManager;
    private DiscordManager discordManager;
    private AIManager aiManager;
    private ReminderEngine reminderEngine;


    public MirraPlugin() {
        mirraPlugin = this;
    }

    @Override
    public void onEnable() {
        this.aiManager = new AIManager();
        this.identityManager = new IdentityManager();
        this.whatsappManager = new WhatsappManager();
        this.discordManager = new DiscordManager();
        this.reminderEngine = new ReminderEngine();
        STEMSystemApp.getInstance().getEventModule().getStemEventBus().register(new StemEventListener());
        STEMSystemApp.getInstance().getEventModule().getStemEventBus().register(new MirraReminderListener());
    }


    @Override
    public void onDisable() {
    }

    public AIManager getAiManager() {
        return aiManager;
    }

    public WhatsappManager getWhatsappManager() {
        return whatsappManager;
    }

    public DiscordManager getDiscordManager() {
        return discordManager;
    }

    public IdentityManager getIdentityManager() {
        return identityManager;
    }

    public ReminderEngine getReminderEngine() {
        return reminderEngine;
    }
}
