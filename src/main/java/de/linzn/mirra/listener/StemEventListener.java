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

package de.linzn.mirra.listener;



import de.linzn.mirra.MirraPlugin;
import de.stem.stemSystem.STEMSystemApp;
import de.stem.stemSystem.modules.eventModule.handler.StemEventHandler;
import de.stem.stemSystem.modules.informationModule.InformationBlock;
import de.stem.stemSystem.modules.informationModule.InformationIntent;
import de.stem.stemSystem.modules.informationModule.events.InformationEvent;
import it.auties.whatsapp.model.jid.Jid;

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
