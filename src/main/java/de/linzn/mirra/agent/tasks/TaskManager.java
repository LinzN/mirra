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

import de.linzn.mirra.MirraPlugin;
import de.linzn.mirra.agent.ChatContext;
import de.linzn.mirra.identitySystem.TokenSource;
import de.linzn.mirra.identitySystem.UserToken;
import de.linzn.stem.api.StemApi;
import de.linzn.stem.api.mqtt.MqttSubscription;
import de.linzn.stem.api.scheduler.ScheduledTask;
import de.linzn.stem.modules.eventModule.StemEvent;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Plans and runs agent tasks. A task is an instruction Mirra carries out as if the owner had just asked for it,
 * at a time, on a cron schedule or when an event happens. The answer is sent to the owner.
 * Tasks are stored in the database and armed again after a restart.
 */
public class TaskManager {

    /* time tasks that were due while STEM was offline still run if they are at most this late */
    private static final Duration MAX_LATE = Duration.ofHours(1);
    /* MQTT messages right after subscribing are retained values, not new events */
    private static final Duration MQTT_SETTLE = Duration.ofSeconds(3);
    private static final Duration STARTUP_DELAY = Duration.ofSeconds(60);
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final MirraPlugin plugin;
    private final TaskRepository repository;
    private final StemApi api;
    private final ZoneId zone;
    private final Map<Integer, AgentTask> tasks = new ConcurrentHashMap<>();
    private final Map<Integer, ScheduledTask> timers = new ConcurrentHashMap<>();
    private final Map<Integer, MqttSubscription> subscriptions = new ConcurrentHashMap<>();
    private final Map<String, Boolean> deviceStates = new ConcurrentHashMap<>();
    /* last result of the condition per task, a task with a condition triggers when it becomes true */
    private final Map<Integer, Boolean> conditionStates = new ConcurrentHashMap<>();
    private final EventCatalog catalog;

    /** Answer of Mirra when a task had nothing to report; such answers are not sent. */
    public static final String NO_REPORT = "NO_REPORT";

    public TaskManager(MirraPlugin plugin, TaskRepository repository) {
        this.plugin = plugin;
        this.repository = repository;
        this.api = plugin.getStemApi();
        this.zone = this.api.system().getZone();
        this.catalog = new EventCatalog(this.api);
    }

    public EventCatalog getCatalog() {
        return catalog;
    }

    /**
     * Load and arm all active tasks.
     */
    public void start() {
        this.catalog.load();
        for (AgentTask task : this.repository.loadActive()) {
            this.tasks.put(task.id(), task);
            this.arm(task, true);
        }
        this.plugin.getPluginLogger().info("Loaded " + this.tasks.size() + " agent tasks");
    }

    public void stop() {
        try {
            this.catalog.save();
        } catch (Exception e) {
            this.plugin.getPluginLogger().warning("Could not save the event catalog: " + e.getMessage());
        }
        this.timers.values().forEach(ScheduledTask::cancel);
        this.subscriptions.values().forEach(MqttSubscription::unsubscribe);
        this.timers.clear();
        this.subscriptions.clear();
    }

    /**
     * Create and arm a task.
     *
     * @throws IllegalArgumentException if the trigger is invalid
     */
    public AgentTask create(ChatContext owner, String instruction, String triggerType, LocalDateTime runAt, String cron,
                            String eventType, String device, String matchValue, String topic, boolean repeating,
                            String eventName, AgentTask.Condition condition) throws SQLException {
        if (owner.isGuest() || owner.isInternal()) {
            throw new IllegalArgumentException("Only registered users can create tasks");
        }
        if (instruction == null || instruction.isBlank()) {
            throw new IllegalArgumentException("The task needs an instruction");
        }
        switch (triggerType) {
            case AgentTask.TIME -> {
                if (runAt == null) {
                    throw new IllegalArgumentException("A time task needs run_at");
                }
                if (runAt.isBefore(LocalDateTime.now(this.zone))) {
                    throw new IllegalArgumentException("run_at is in the past (now is " + LocalDateTime.now(this.zone).format(FORMAT) + ")");
                }
            }
            case AgentTask.CRON -> {
                if (cron == null || cron.isBlank()) {
                    throw new IllegalArgumentException("A cron task needs a cron pattern");
                }
            }
            case AgentTask.EVENT -> {
                if (eventType == null || !AgentTask.EVENTS.contains(eventType)) {
                    throw new IllegalArgumentException("Unknown event, use one of " + AgentTask.EVENTS);
                }
                if (eventType.equals("mqtt") && (topic == null || topic.isBlank())) {
                    throw new IllegalArgumentException("An mqtt event needs a topic");
                }
                if (eventType.equals("stem_event") && (eventName == null || eventName.isBlank())) {
                    throw new IllegalArgumentException("A stem_event task needs event_name, see list_event_types");
                }
                if (condition != null && !eventType.equals("stem_event") && !eventType.equals("mqtt")) {
                    throw new IllegalArgumentException("Conditions are only possible for stem_event and mqtt");
                }
            }
            default -> throw new IllegalArgumentException("trigger_type must be time, cron or event");
        }
        if (condition != null && (condition.field() == null || condition.field().isBlank() || condition.value() == null
                || !AgentTask.Condition.OPERATORS.contains(condition.operator()))) {
            throw new IllegalArgumentException("A condition needs field, value and one of the operators " + AgentTask.Condition.OPERATORS);
        }
        AgentTask task = new AgentTask(0, owner.userToken().getSource().name(), owner.userToken().getName(), owner.name(),
                instruction.trim(), triggerType, runAt, blankToNull(cron), eventType, blankToNull(device), blankToNull(matchValue),
                blankToNull(topic), repeating || AgentTask.CRON.equals(triggerType), LocalDateTime.now(this.zone), null, 0,
                blankToNull(eventName), condition);
        if (AgentTask.CRON.equals(triggerType)) {
            /* validate the pattern before storing */
            ScheduledTask probe = this.api.scheduler().runCron(() -> {
            }, cron);
            boolean valid = !probe.isCanceled();
            probe.cancel();
            if (!valid) {
                throw new IllegalArgumentException("Invalid cron pattern '" + cron + "', use 5 fields: minute hour day month weekday");
            }
        }
        task = this.repository.insert(task);
        this.tasks.put(task.id(), task);
        this.arm(task, false);
        this.plugin.getPluginLogger().info("Task #" + task.id() + " for " + task.ownerName() + ": " + task.describeTrigger() + " -> " + task.instruction());
        return task;
    }

    /**
     * @param ownerName only tasks of this owner, null for all
     */
    public List<AgentTask> list(String ownerName) {
        return this.tasks.values().stream()
                .filter(task -> ownerName == null || task.ownerName().equalsIgnoreCase(ownerName))
                .sorted(Comparator.comparingInt(AgentTask::id))
                .toList();
    }

    public Optional<AgentTask> get(int id) {
        return Optional.ofNullable(this.tasks.get(id));
    }

    public boolean cancel(int id) {
        AgentTask task = this.tasks.remove(id);
        if (task == null) {
            return false;
        }
        this.disarm(id);
        this.repository.deactivate(id);
        this.plugin.getPluginLogger().info("Task #" + id + " cancelled");
        return true;
    }

    /* ---------- triggers ---------- */

    private void arm(AgentTask task, boolean afterRestart) {
        switch (task.triggerType()) {
            case AgentTask.TIME -> {
                long delay = Duration.between(LocalDateTime.now(this.zone), task.runAt()).toMillis();
                if (delay < 0 && afterRestart && -delay > MAX_LATE.toMillis()) {
                    this.tasks.remove(task.id());
                    this.repository.deactivate(task.id());
                    String text = "⚠️ Die geplante Aufgabe #" + task.id() + " (" + task.instruction() + ", "
                            + task.runAt().format(FORMAT) + ") wurde verpasst, weil STEM nicht lief.";
                    this.api.scheduler().runLater(() -> this.notifyOwner(task, text), STARTUP_DELAY.toSeconds(), TimeUnit.SECONDS);
                    return;
                }
                if (afterRestart) {
                    /* give Discord and WhatsApp time to connect before overdue tasks run */
                    delay = Math.max(delay, STARTUP_DELAY.toMillis());
                }
                long wait = Math.max(0, delay);
                this.timers.put(task.id(), this.api.scheduler().runLater(() -> this.trigger(task, "planned time " + task.runAt().format(FORMAT)),
                        wait, TimeUnit.MILLISECONDS));
            }
            case AgentTask.CRON -> this.timers.put(task.id(), this.api.scheduler().runCron(() -> this.trigger(task, "cron " + task.cron()), task.cron()));
            case AgentTask.EVENT -> {
                if ("mqtt".equals(task.eventType())) {
                    Instant since = Instant.now();
                    this.subscriptions.put(task.id(), this.api.mqtt().subscribe(task.topic(), (topic, payload) -> {
                        if (Duration.between(since, Instant.now()).compareTo(MQTT_SETTLE) < 0) {
                            return;
                        }
                        String text = new String(payload, StandardCharsets.UTF_8);
                        if (!task.matchesValue(text)) {
                            return;
                        }
                        JSONObject data;
                        try {
                            data = new JSONObject(text);
                        } catch (Exception e) {
                            data = new JSONObject().put("payload", text);
                        }
                        data.put("topic", topic);
                        if (this.conditionFires(task, data)) {
                            this.trigger(task, "MQTT message on " + topic + ": " + (text.length() > 500 ? text.substring(0, 500) + "..." : text));
                        }
                    }));
                }
            }
            default -> this.plugin.getPluginLogger().warning("Task #" + task.id() + " has an unknown trigger " + task.triggerType());
        }
    }

    /**
     * @return true if the task has no condition, or its condition just became true
     */
    private boolean conditionFires(AgentTask task, JSONObject data) {
        if (task.condition() == null) {
            return true;
        }
        Boolean result = task.condition().test(data);
        if (result == null) {
            return false;
        }
        Boolean previous = this.conditionStates.put(task.id(), result);
        return result && !Boolean.TRUE.equals(previous);
    }

    /**
     * Called for every STEM event: updates the event catalog and runs stem_event tasks.
     */
    public void onStemEvent(StemEvent event) {
        String name = event.getName();
        JSONObject[] data = new JSONObject[1];
        Supplier<JSONObject> describe = () -> {
            if (data[0] == null) {
                data[0] = EventInspector.describe(event);
            }
            return data[0];
        };
        this.catalog.seen(name, describe);
        for (AgentTask task : this.tasks.values()) {
            if (!AgentTask.EVENT.equals(task.triggerType()) || !"stem_event".equals(task.eventType()) || !name.equalsIgnoreCase(task.eventName())) {
                continue;
            }
            String text = describe.get().toString();
            if (task.matchesDevice(text) && task.matchesValue(text) && this.conditionFires(task, describe.get())) {
                this.trigger(task, "STEM event " + name + ": " + (text.length() > 1500 ? text.substring(0, 1500) + "..." : text));
            }
        }
    }

    private void disarm(int id) {
        ScheduledTask timer = this.timers.remove(id);
        if (timer != null) {
            timer.cancel();
        }
        this.conditionStates.remove(id);
        MqttSubscription subscription = this.subscriptions.remove(id);
        if (subscription != null) {
            subscription.unsubscribe();
        }
    }

    /**
     * Called by the event listener for every relevant STEM event.
     *
     * @param eventType one of {@link AgentTask#EVENTS}
     * @param device    name of the device, may be null
     * @param text      text to match against {@code match_value}, may be null
     * @param details   description of the event for Mirra
     */
    public void onEvent(String eventType, String device, String text, String details) {
        for (AgentTask task : this.tasks.values()) {
            if (AgentTask.EVENT.equals(task.triggerType()) && eventType.equals(task.eventType())
                    && task.matchesDevice(device) && task.matchesValue(text)) {
                this.trigger(task, details);
            }
        }
    }

    /**
     * A switch reported its state. Only real changes become device_on / device_off events.
     */
    public void onDeviceState(String device, boolean on) {
        Boolean previous = this.deviceStates.put(device, on);
        if (previous != null && previous != on) {
            this.onEvent(on ? "device_on" : "device_off", device, null, "Device " + device + " switched " + (on ? "on" : "off"));
        }
    }

    private void trigger(AgentTask task, String details) {
        if (!task.repeating()) {
            /* remove first, so a second event cannot run a one-time task twice */
            if (this.tasks.remove(task.id()) == null) {
                return;
            }
            this.disarm(task.id());
            this.repository.deactivate(task.id());
        }
        this.repository.markRun(task.id());
        this.api.scheduler().runAsync(() -> this.execute(task, details));
    }

    private void execute(AgentTask task, String details) {
        TokenSource source;
        try {
            source = TokenSource.valueOf(task.ownerSource());
        } catch (IllegalArgumentException e) {
            this.plugin.getPluginLogger().warning("Task #" + task.id() + " has an unknown owner source " + task.ownerSource());
            return;
        }
        UserToken token = this.plugin.getIdentityManager().getOrCreateUserToken(task.ownerToken(), source);
        ChatContext chat = this.plugin.chatContext(token, task.ownerName());
        if (chat.isGuest()) {
            this.plugin.getPluginLogger().warning("Owner of task #" + task.id() + " is not registered anymore, task skipped");
            this.cancel(task.id());
            return;
        }
        this.plugin.getPluginLogger().info("Running task #" + task.id() + " for " + task.ownerName() + " (" + details + ")");
        String message = "(Automatic task #" + task.id() + " that " + task.ownerName() + " created on " + task.created().format(FORMAT)
                + ". Trigger: " + task.describeTrigger() + ". What happened: " + details + ".)\n"
                + "Task: " + task.instruction() + "\n"
                + task.ownerName() + " already confirmed this task: carry it out now with your tools without asking back, "
                + "then tell " + task.ownerName() + " in one or two short sentences what you did. "
                + "If the task says to report only in certain cases and none of them applies, answer exactly " + NO_REPORT + ".";
        String answer = this.plugin.getAgent().chatAutomatic(chat, message);
        if (answer != null && answer.trim().toUpperCase(Locale.ROOT).startsWith(NO_REPORT)) {
            this.plugin.getPluginLogger().debug("Task #" + task.id() + ": nothing to report");
            return;
        }
        this.plugin.deliverAnswer(chat, answer, false);
    }

    private void notifyOwner(AgentTask task, String text) {
        try {
            UserToken token = this.plugin.getIdentityManager().getOrCreateUserToken(task.ownerToken(), TokenSource.valueOf(task.ownerSource()));
            if (this.plugin.getMessenger().canSend(token)) {
                this.plugin.getMessenger().sendText(token, text);
            }
        } catch (Exception e) {
            this.plugin.getPluginLogger().warning("Could not notify owner of task #" + task.id() + ": " + e.getMessage());
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
