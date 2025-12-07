package de.linzn.mirra.whatsapp.listener;

import com.github.auties00.cobalt.client.WhatsAppClient;
import com.github.auties00.cobalt.client.WhatsAppClientDisconnectReason;
import com.github.auties00.cobalt.client.WhatsAppClientListener;
import de.stem.stemSystem.STEMSystemApp;


public class OnDisconnectedListener implements WhatsAppClientListener {

    @Override
    public void onDisconnected(WhatsAppClient whatsapp, WhatsAppClientDisconnectReason reason) {
        STEMSystemApp.LOGGER.ERROR("Whatsapp disconnected!");
    }
}
