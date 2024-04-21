package de.linzn.mirra.whatsapp.listener;

import de.stem.stemSystem.STEMSystemApp;
import it.auties.whatsapp.api.DisconnectReason;
import it.auties.whatsapp.listener.OnDisconnected;

public class OnDisconnectedListener implements OnDisconnected {

    @Override
    public void onDisconnected(DisconnectReason reason) {
        STEMSystemApp.LOGGER.ERROR("Whatsapp disconnected!");
    }
}
