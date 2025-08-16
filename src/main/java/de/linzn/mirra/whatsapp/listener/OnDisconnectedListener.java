package de.linzn.mirra.whatsapp.listener;

import de.stem.stemSystem.STEMSystemApp;
import it.auties.whatsapp.api.WhatsappDisconnectReason;
import it.auties.whatsapp.api.WhatsappListener;


public class OnDisconnectedListener implements WhatsappListener {

    @Override
    public void onDisconnected(WhatsappDisconnectReason reason) {
        STEMSystemApp.LOGGER.ERROR("Whatsapp disconnected!");
    }
}
