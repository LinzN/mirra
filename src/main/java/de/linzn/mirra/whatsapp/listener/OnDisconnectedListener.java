package de.linzn.mirra.whatsapp.listener;

import de.stem.stemSystem.STEMSystemApp;
import it.auties.whatsapp.api.DisconnectReason;
import it.auties.whatsapp.api.Listener;


public class OnDisconnectedListener implements Listener {

    @Override
    public void onDisconnected(DisconnectReason reason) {
        STEMSystemApp.LOGGER.ERROR("Whatsapp disconnected!");
    }
}
