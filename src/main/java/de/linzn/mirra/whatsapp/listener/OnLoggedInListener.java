package de.linzn.mirra.whatsapp.listener;

import com.github.auties00.cobalt.client.WhatsAppClient;
import com.github.auties00.cobalt.client.WhatsAppClientListener;
import de.stem.stemSystem.STEMSystemApp;


public class OnLoggedInListener implements WhatsAppClientListener {
    @Override
    public void onLoggedIn(WhatsAppClient whatsapp) {
        STEMSystemApp.LOGGER.CONFIG("Whatsapp login successful");
    }
}
