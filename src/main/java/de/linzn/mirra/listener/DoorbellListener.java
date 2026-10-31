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

package de.linzn.mirra.listener;

import de.linzn.homeDevices.events.records.MQTTDoorRingEvent;
import de.linzn.mirra.MirraConfig;
import de.linzn.mirra.MirraPlugin;
import de.linzn.mirra.voice.MirraVoice;
import de.linzn.stem.modules.eventModule.handler.StemEventHandler;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rings on the voice devices when a door bell of home-devices is pressed: the door bell sound, then optionally
 * {@code voice.doorbell.text}. Devices in the middle of a conversation are interrupted.
 */
public class DoorbellListener {

    /* one press can arrive twice (MQTT retries); a real second press a few seconds later rings again */
    private static final long DEDUPE_MS = 2500;

    private final MirraPlugin plugin;
    private final MirraConfig config;
    private final AtomicLong lastRing = new AtomicLong();

    public DoorbellListener(MirraPlugin plugin, MirraConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    @StemEventHandler()
    public void onDoorRing(MQTTDoorRingEvent event) {
        if (!this.config.doorbellEnabled) {
            return;
        }
        long now = System.currentTimeMillis();
        long last = this.lastRing.get();
        if (now - last < DEDUPE_MS || !this.lastRing.compareAndSet(last, now)) {
            return;
        }
        String bell = event.doorRingDevice().getConfigName();
        MirraVoice voice = this.plugin.getVoice();
        List<MirraVoice.Device> targets = voice.devices().stream().filter(MirraVoice.Device::online)
                .filter(device -> this.config.doorbellDevices.isEmpty() || this.config.doorbellDevices.stream().anyMatch(wanted ->
                        wanted.equalsIgnoreCase(device.id()) || (device.name() != null && wanted.equalsIgnoreCase(device.name()))))
                .toList();
        if (targets.isEmpty()) {
            return;
        }
        this.plugin.getPluginLogger().info("Door bell " + bell + ": ringing on " + targets.stream().map(MirraVoice.Device::name).toList());
        String text = this.config.doorbellText.replace("{bell}", bell == null ? "" : bell).trim();
        for (MirraVoice.Device device : targets) {
            /* every device on its own thread: the sound starts everywhere at the same time */
            this.plugin.getStemApi().scheduler().runAsync(() -> {
                try {
                    voice.play(device.id(), MirraVoice.SOUND_DOORBELL);
                    if (!text.isEmpty()) {
                        voice.speak(device.id(), text);
                    }
                } catch (Exception e) {
                    this.plugin.getPluginLogger().warning("Door bell on " + device.name() + ": " + e.getMessage());
                }
            });
        }
    }
}
