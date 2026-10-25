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

import de.linzn.mirra.MirraPlugin;
import de.linzn.stem.modules.eventModule.handler.StemEventHandler;
import de.linzn.stem.modules.informationModule.InformationBlock;
import de.linzn.stem.modules.informationModule.InformationIntent;
import de.linzn.stem.modules.informationModule.events.InformationEvent;

public class StemEventListener {

    private final MirraPlugin plugin;

    public StemEventListener(MirraPlugin plugin) {
        this.plugin = plugin;
    }

    @StemEventHandler()
    public void onInformationEvent(InformationEvent informationEvent) {
        InformationBlock informationBlock = informationEvent.getInformationBlock();
        if (!informationBlock.hasIntent(InformationIntent.NOTIFY_USER)) {
            return;
        }
        String message = informationBlock.getLongDescription();
        /* network calls, do not block the event bus */
        this.plugin.getStemApi().scheduler().runAsync(() -> {
            this.plugin.getWhatsappManager().sendTextMessage(this.plugin.getWhatsappManager().defaultJID, message);
            this.plugin.getDiscordManager().sendPrivateMessage(this.plugin.getDiscordManager().defaultUID, message);
        });
    }
}
