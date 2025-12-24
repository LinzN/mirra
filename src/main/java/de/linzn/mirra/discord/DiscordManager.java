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

package de.linzn.mirra.discord;

import de.linzn.mirra.MirraPlugin;
import de.linzn.mirra.discord.listener.DiscordReceiveListener;
import de.stem.stemSystem.STEMSystemApp;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.GatewayIntent;

public class DiscordManager {
    public String defaultUID;
    private JDA jda;

    public DiscordManager() {
        String token = MirraPlugin.mirraPlugin.getDefaultConfig().getString("discord.token", "xxxx");
        this.defaultUID = MirraPlugin.mirraPlugin.getDefaultConfig().getString("discord.defaultUID", "xxxx");
        MirraPlugin.mirraPlugin.getDefaultConfig().save();

        STEMSystemApp.getInstance().getScheduler().runTask(MirraPlugin.mirraPlugin, () -> {
            JDABuilder jdaBuilder = JDABuilder.createLight(token);
            jdaBuilder.setAutoReconnect(true);
            jdaBuilder.enableIntents(GatewayIntent.MESSAGE_CONTENT);
            jdaBuilder.addEventListeners(new DiscordReceiveListener());
            jda = jdaBuilder.build();
            try {
                jda.awaitReady();
                STEMSystemApp.LOGGER.CORE("Login Discord API success!");
            } catch (InterruptedException e) {
                STEMSystemApp.LOGGER.ERROR("Login Discord API failed!");
                STEMSystemApp.LOGGER.ERROR(e);
            }
            jda.getPresence().setActivity(Activity.playing("Working on MirraNET"));
            jda.updateCommands().addCommands(
                    Commands.slash("ping", "Pong")
            ).queue();
        });
    }


    public JDA getJda() {
        return jda;
    }
}
