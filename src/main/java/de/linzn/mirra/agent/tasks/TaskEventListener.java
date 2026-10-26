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

package de.linzn.mirra.agent.tasks;

import de.linzn.homeDevices.events.records.DeviceUpdateEvent;
import de.linzn.homeDevices.events.records.MQTTDoorRingEvent;
import de.linzn.homeDevices.events.records.MQTTPrintEndEvent;
import de.linzn.homeDevices.events.records.MQTTSnapmakerPrintEndEvent;
import de.linzn.stem.modules.eventModule.StemEvent;
import de.linzn.stem.modules.eventModule.handler.StemEventHandler;
import de.linzn.stem.modules.eventModule.handler.StemEventPriority;
import de.linzn.stem.modules.informationModule.InformationBlock;
import de.linzn.stem.modules.informationModule.events.InformationEvent;

/**
 * Turns STEM and home-devices events into task events.
 */
public class TaskEventListener {

    private final TaskManager manager;

    public TaskEventListener(TaskManager manager) {
        this.manager = manager;
    }

    @StemEventHandler()
    public void onSnapmakerPrintEnd(MQTTSnapmakerPrintEndEvent event) {
        String device = event.snapmakerPrinter().getConfigName();
        String type = switch (event.eventType()) {
            case "print_finished" -> "print_finished";
            case "print_failed" -> "print_failed";
            default -> "print_cancelled";
        };
        this.manager.onEvent(type, device, event.printName(),
                "3D printer " + device + ": " + type.replace('_', ' ') + " for " + event.printName());
    }

    @StemEventHandler()
    public void onAnkermakePrintEnd(MQTTPrintEndEvent event) {
        String device = event.ankermakePrinter().getConfigName();
        this.manager.onEvent("print_finished", device, event.printName(), "3D printer " + device + ": print finished for " + event.printName());
    }

    @StemEventHandler()
    public void onDeviceUpdate(DeviceUpdateEvent event) {
        this.manager.onDeviceState(event.mqttSwitch().getConfigName(), event.newStatus());
    }

    @StemEventHandler()
    public void onDoorRing(MQTTDoorRingEvent event) {
        String device = event.doorRingDevice().getConfigName();
        this.manager.onEvent("door_ring", device, null, "Door bell " + device + " rang");
    }

    @StemEventHandler()
    public void onInformation(InformationEvent event) {
        InformationBlock block = event.getInformationBlock();
        String text = block.getName() + " " + block.getDescription();
        this.manager.onEvent("information", null, text, "Information block '" + block.getName() + "': " + block.getDescription());
    }

    /**
     * Receives every STEM event (STEM delivers all events to handlers of the type StemEvent).
     */
    @StemEventHandler(priority = StemEventPriority.LOW)
    public void onAnyEvent(StemEvent event) {
        this.manager.onStemEvent(event);
    }
}
