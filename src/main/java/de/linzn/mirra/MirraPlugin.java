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

package de.linzn.mirra;


import de.linzn.mirra.core.AIManager;
import de.linzn.mirra.core.reminder.ReminderEngine;
import de.linzn.mirra.discord.DiscordManager;
import de.linzn.mirra.identitySystem.IdentityManager;
import de.linzn.mirra.listener.MirraReminderListener;
import de.linzn.mirra.listener.StemEventListener;
import de.linzn.mirra.whatsapp.WhatsappManager;
import de.linzn.stem.STEMApp;
import de.linzn.stem.modules.pluginModule.STEMPlugin;


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
        STEMApp.getInstance().getEventModule().getStemEventBus().register(new StemEventListener());
        STEMApp.getInstance().getEventModule().getStemEventBus().register(new MirraReminderListener());
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
