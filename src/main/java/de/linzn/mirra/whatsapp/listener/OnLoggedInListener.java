package de.linzn.mirra.whatsapp.listener;

import de.stem.stemSystem.STEMSystemApp;
import it.auties.whatsapp.api.Listener;


public class OnLoggedInListener implements Listener {
    @Override
    public void onLoggedIn() {
        STEMSystemApp.LOGGER.CONFIG("Whatsapp login successful");
    }
}
