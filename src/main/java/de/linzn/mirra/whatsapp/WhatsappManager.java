package de.linzn.mirra.whatsapp;

import de.linzn.mirra.MirraPlugin;
import de.linzn.mirra.whatsapp.listener.OnDisconnectedListener;
import de.linzn.mirra.whatsapp.listener.OnLoggedInListener;
import de.linzn.mirra.whatsapp.listener.OnNewChatMessageListener;
import de.stem.stemSystem.STEMSystemApp;
import it.auties.whatsapp.api.QrHandler;
import it.auties.whatsapp.api.TextPreviewSetting;
import it.auties.whatsapp.api.Whatsapp;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class WhatsappManager {

    public String defaultJID;
    public UUID sessionUUID;
    private Whatsapp whatsapp;


    public WhatsappManager() {
        this.defaultJID = MirraPlugin.mirraPlugin.getDefaultConfig().getString("whatsapp.defaultJID", "xxxx");
        this.sessionUUID = UUID.fromString(MirraPlugin.mirraPlugin.getDefaultConfig().getString("whatsapp.sessionUUID", "dd8d7aca-a6cf-468f-a4bf-51c3b8ae7c8a"));
        MirraPlugin.mirraPlugin.getDefaultConfig().save();
        try {
            this.whatsapp = Whatsapp.webBuilder()
                    .lastConnection()
                    .textPreviewSetting(TextPreviewSetting.DISABLED) // fix preview
                    .unregistered(QrHandler.toTerminal())
                    .addListener(new OnLoggedInListener())
                    .addListener(new OnDisconnectedListener())
                    .addListener(new OnNewChatMessageListener())
                    .connect()
                    .join();
            //this.whatsapp.store().setTextPreviewSetting(WhatsappTextPreviewPolicy.DISABLED);
            Thread.sleep(1000);
            STEMSystemApp.getInstance().getScheduler().runRepeatScheduler(MirraPlugin.mirraPlugin, this::registerReconnectHandler, 30, 30, TimeUnit.SECONDS);
        } catch (Exception e) {
            STEMSystemApp.LOGGER.ERROR(e);
        }
    }

    private void registerReconnectHandler() {
        if (!whatsapp.isConnected()) {
            whatsapp.reconnect();
            STEMSystemApp.LOGGER.ERROR("Whatsapp is no more connected. Try to reconnect");
            whatsapp.changePresence(true);
        } else {
            STEMSystemApp.LOGGER.DEBUG("Whatsapp still connected!");
            whatsapp.changePresence(true);
        }
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        whatsapp.changePresence(false);
    }

    public Whatsapp getWhatsapp() {
        return whatsapp;
    }
}
