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

import java.time.LocalDateTime;
import java.util.Locale;

/**
 * A task Mirra carries out on her own: an instruction in natural language and a trigger.
 *
 * @param id          database id
 * @param ownerSource token source of the person who created the task (DISCORD, WHATSAPP)
 * @param ownerToken  token name of that person (Discord id, WhatsApp JID); the answer goes there
 * @param ownerName   name of the person
 * @param instruction what Mirra should do, e.g. "Schalte den 3D-Drucker aus"
 * @param triggerType time, cron or event
 * @param runAt       local time of a time task
 * @param cron        cron pattern of a cron task
 * @param eventType   event of an event task, see {@link #EVENTS}
 * @param device      device name filter (part of the name, case insensitive), may be null
 * @param matchValue  text that must be contained in the information block or MQTT payload, may be null
 * @param topic       MQTT topic filter of an mqtt event
 * @param repeating   event tasks: run on every event instead of once
 * @param eventName   stem_event tasks: name of the STEM event class, e.g. MQTTSnapmakerPrintEndEvent
 * @param condition   optional condition on a field of the event or MQTT payload; tasks with a condition trigger
 *                    when it becomes true
 */
public record AgentTask(int id, String ownerSource, String ownerToken, String ownerName, String instruction,
                        String triggerType, LocalDateTime runAt, String cron, String eventType, String device,
                        String matchValue, String topic, boolean repeating, LocalDateTime created,
                        LocalDateTime lastRun, int runCount, String eventName, Condition condition) {

    /**
     * Compares a field of an event with a value.
     *
     * @param field    dotted path in the event data, e.g. {@code environmentSensor.temperature}
     * @param operator {@code < <= > >= == != contains}
     * @param value    value to compare with; numeric comparison if both sides are numbers
     */
    public record Condition(String field, String operator, String value) {

        public static final java.util.List<String> OPERATORS = java.util.List.of("<", "<=", ">", ">=", "==", "!=", "contains");

        /**
         * @return true, false, or null if the field does not exist
         */
        public Boolean test(org.json.JSONObject data) {
            Object current = data;
            for (String part : this.field.split("\\.")) {
                if (!(current instanceof org.json.JSONObject object) || !object.has(part)) {
                    return null;
                }
                current = object.get(part);
            }
            String actual = String.valueOf(current);
            Double left = number(actual);
            Double right = number(this.value);
            if (left != null && right != null && !this.operator.equals("contains")) {
                int compare = Double.compare(left, right);
                return switch (this.operator) {
                    case "<" -> compare < 0;
                    case "<=" -> compare <= 0;
                    case ">" -> compare > 0;
                    case ">=" -> compare >= 0;
                    case "!=" -> compare != 0;
                    default -> compare == 0;
                };
            }
            return switch (this.operator) {
                case "contains" -> actual.toLowerCase(Locale.ROOT).contains(this.value.toLowerCase(Locale.ROOT));
                case "!=" -> !actual.equalsIgnoreCase(this.value);
                case "==" -> actual.equalsIgnoreCase(this.value);
                default -> null;
            };
        }

        private static Double number(String text) {
            try {
                return Double.parseDouble(text.trim().replace(',', '.'));
            } catch (NumberFormatException | NullPointerException e) {
                return null;
            }
        }

        @Override
        public String toString() {
            return this.field + " " + this.operator + " " + this.value;
        }
    }

    public static final String TIME = "time";
    public static final String CRON = "cron";
    public static final String EVENT = "event";

    /**
     * Events a task can wait for.
     */
    public static final java.util.List<String> EVENTS = java.util.List.of(
            "print_finished", "print_failed", "print_cancelled", "device_on", "device_off", "door_ring", "information", "mqtt", "stem_event");

    public AgentTask withId(int newId) {
        return new AgentTask(newId, ownerSource, ownerToken, ownerName, instruction, triggerType, runAt, cron, eventType,
                device, matchValue, topic, repeating, created, lastRun, runCount, eventName, condition);
    }

    /**
     * @return true if the device filter matches (or there is none)
     */
    public boolean matchesDevice(String deviceName) {
        return this.device == null || this.device.isBlank()
                || (deviceName != null && deviceName.toLowerCase(Locale.ROOT).contains(this.device.toLowerCase(Locale.ROOT)));
    }

    /**
     * @return true if the text filter matches (or there is none)
     */
    public boolean matchesValue(String text) {
        return this.matchValue == null || this.matchValue.isBlank()
                || (text != null && text.toLowerCase(Locale.ROOT).contains(this.matchValue.toLowerCase(Locale.ROOT)));
    }

    /**
     * @return short human readable description of the trigger
     */
    public String describeTrigger() {
        return switch (this.triggerType) {
            case TIME -> "at " + this.runAt;
            case CRON -> "cron '" + this.cron + "'";
            default -> {
                StringBuilder builder = new StringBuilder("event ").append(this.eventType);
                if (this.eventName != null && !this.eventName.isBlank()) {
                    builder.append(" ").append(this.eventName);
                }
                if (this.device != null && !this.device.isBlank()) {
                    builder.append(" device~'").append(this.device).append("'");
                }
                if (this.topic != null && !this.topic.isBlank()) {
                    builder.append(" topic '").append(this.topic).append("'");
                }
                if (this.matchValue != null && !this.matchValue.isBlank()) {
                    builder.append(" containing '").append(this.matchValue).append("'");
                }
                if (this.condition != null) {
                    builder.append(" when ").append(this.condition);
                }
                builder.append(this.repeating ? " (every time)" : " (once)");
                yield builder.toString();
            }
        };
    }
}
