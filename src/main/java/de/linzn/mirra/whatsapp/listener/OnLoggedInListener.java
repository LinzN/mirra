package de.linzn.mirra.whatsapp.listener;

import de.stem.stemSystem.STEMSystemApp;
import it.auties.whatsapp.listener.OnLoggedIn;

public class OnLoggedInListener implements OnLoggedIn {
    @Override
    public void onLoggedIn() {
        STEMSystemApp.LOGGER.CONFIG("Whatsapp login successful");
    }
}
