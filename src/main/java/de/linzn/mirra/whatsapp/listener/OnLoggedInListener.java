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

package de.linzn.mirra.whatsapp.listener;

import com.github.auties00.cobalt.client.WhatsAppClient;
import com.github.auties00.cobalt.client.WhatsAppClientListener;
import de.linzn.stem.STEMApp;


public class OnLoggedInListener implements WhatsAppClientListener {
    @Override
    public void onLoggedIn(WhatsAppClient whatsapp) {
        STEMApp.LOGGER.CONFIG("Whatsapp login successful");
    }
}
