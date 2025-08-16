package de.linzn.mirra.whatsapp.listener;

import de.stem.stemSystem.STEMSystemApp;
import it.auties.whatsapp.api.WhatsappListener;


public class OnLoggedInListener implements WhatsappListener {
    @Override
    public void onLoggedIn() {
        STEMSystemApp.LOGGER.CONFIG("Whatsapp login successful");
    }
}
