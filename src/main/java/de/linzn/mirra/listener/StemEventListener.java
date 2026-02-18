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

package de.linzn.mirra.listener;


import de.linzn.evolutionApiJava.api.Jid;
import de.linzn.mirra.MirraPlugin;
import de.linzn.stem.STEMApp;
import de.linzn.stem.modules.eventModule.handler.StemEventHandler;
import de.linzn.stem.modules.informationModule.InformationBlock;
import de.linzn.stem.modules.informationModule.InformationIntent;
import de.linzn.stem.modules.informationModule.events.InformationEvent;


public class StemEventListener {

    @StemEventHandler()
    public void onInformationEventWhatsapp(InformationEvent informationEvent) {
        InformationBlock informationBlock = informationEvent.getInformationBlock();
        if (informationBlock.hasIntent(InformationIntent.NOTIFY_USER)) {
            try {
                MirraPlugin.mirraPlugin.getWhatsappManager().getEvolutionApi().sendTextMessage(new Jid(MirraPlugin.mirraPlugin.getWhatsappManager().defaultJID), informationBlock.getLongDescription());
            } catch (Exception e) {
                STEMApp.LOGGER.ERROR(e);
            }

        }
    }

    @StemEventHandler()
    public void onInformationEventDiscord(InformationEvent informationEvent) {
        InformationBlock informationBlock = informationEvent.getInformationBlock();
        if (informationBlock.hasIntent(InformationIntent.NOTIFY_USER)) {
            try {
                MirraPlugin.mirraPlugin.getDiscordManager().getJda().retrieveUserById(MirraPlugin.mirraPlugin.getDiscordManager().defaultUID).complete().openPrivateChannel().complete().sendMessage(informationBlock.getLongDescription()).complete();
            } catch (Exception e) {
                STEMApp.LOGGER.ERROR(e);
            }
        }
    }
}
