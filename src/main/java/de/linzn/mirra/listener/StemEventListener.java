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

package de.linzn.mirra.listener;


import com.github.auties00.cobalt.model.jid.Jid;
import de.linzn.mirra.MirraPlugin;
import de.stem.stemSystem.STEMSystemApp;
import de.stem.stemSystem.modules.eventModule.handler.StemEventHandler;
import de.stem.stemSystem.modules.informationModule.InformationBlock;
import de.stem.stemSystem.modules.informationModule.InformationIntent;
import de.stem.stemSystem.modules.informationModule.events.InformationEvent;

public class StemEventListener {

    @StemEventHandler()
    public void onInformationEventWhatsapp(InformationEvent informationEvent) {
        InformationBlock informationBlock = informationEvent.getInformationBlock();
        if (informationBlock.hasIntent(InformationIntent.NOTIFY_USER)) {
            try {
                MirraPlugin.mirraPlugin.getWhatsappManager().getWhatsapp().sendChatMessage(Jid.of(MirraPlugin.mirraPlugin.getWhatsappManager().defaultJID), informationBlock.getLongDescription());
            } catch (Exception e) {
                STEMSystemApp.LOGGER.ERROR(e);
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
                STEMSystemApp.LOGGER.ERROR(e);
            }
        }
    }
}
