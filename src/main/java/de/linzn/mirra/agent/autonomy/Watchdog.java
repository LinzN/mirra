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

package de.linzn.mirra.agent.autonomy;

import de.linzn.mirra.MirraConfig;
import de.linzn.mirra.MirraPlugin;
import de.linzn.mirra.agent.ChatContext;
import de.linzn.mirra.agent.tasks.TaskManager;
import de.linzn.mirra.identitySystem.IdentityUser;
import de.linzn.mirra.identitySystem.UserToken;
import de.linzn.stem.api.StemApi;
import de.linzn.stem.api.system.LogEntry;
import de.linzn.stem.modules.healthModule.HealthCheck;
import de.linzn.stem.modules.healthModule.HealthCheckFeedback;
import de.linzn.stem.modules.healthModule.HealthCheckLevel;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lets Mirra act on her own when something goes wrong in STEM. Every few minutes it collects new problems
 * (failed health checks, errors in the STEM log) and, if there are any, runs an agent turn on behalf of the approver:
 * Mirra analyzes the problems with her tools, fixes what is safe (mode "fix") and reports to the approver.
 * The same problem is handled at most once per cooldown, and the number of runs per day is limited.
 */
public class Watchdog {

    private static final int MAX_PROBLEMS = 15;

    private final MirraPlugin plugin;
    private final MirraConfig config;
    private final StemApi api;
    private final java.util.function.BooleanSupplier paused;
    private final Map<String, Instant> handled = new ConcurrentHashMap<>();
    private final AtomicInteger runsToday = new AtomicInteger();
    private volatile LocalDate runsDay;
    private volatile Instant lastLogCheck = Instant.now();
    private volatile boolean running;

    /**
     * @param paused tells whether OpenAI can be used right now (false while the credits are used up)
     */
    public Watchdog(MirraPlugin plugin, MirraConfig config, java.util.function.BooleanSupplier paused) {
        this.plugin = plugin;
        this.config = config;
        this.api = plugin.getStemApi();
        this.paused = paused;
    }

    public void start() {
        /* the first round runs after the interval, so problems during the start of STEM are not reported twice */
        this.api.scheduler().runRepeating(this::check, this.config.autonomyIntervalMinutes, this.config.autonomyIntervalMinutes, TimeUnit.MINUTES);
        this.plugin.getPluginLogger().info("Watchdog started, mode " + (this.config.autonomyMayFix ? "fix" : "report")
                + ", every " + this.config.autonomyIntervalMinutes + " min");
    }

    /**
     * One round: collect new problems and let Mirra handle them.
     */
    public void check() {
        if (this.running) {
            return;
        }
        this.running = true;
        try {
            List<String> problems = this.collect();
            if (problems.isEmpty()) {
                return;
            }
            if (this.paused != null && this.paused.getAsBoolean()) {
                this.plugin.getPluginLogger().warning("Watchdog: " + problems.size() + " problem(s), but no OpenAI credits");
                return;
            }
            LocalDate today = LocalDate.now(this.api.system().getZone());
            if (!today.equals(this.runsDay)) {
                this.runsDay = today;
                this.runsToday.set(0);
            }
            if (this.runsToday.incrementAndGet() > this.config.autonomyMaxRunsPerDay) {
                this.plugin.getPluginLogger().warning("Watchdog: daily limit reached, " + problems.size() + " problem(s) not handled");
                return;
            }
            this.handle(problems);
        } catch (Exception e) {
            this.plugin.getPluginLogger().error("Watchdog round failed", e);
        } finally {
            this.running = false;
        }
    }

    /**
     * @return descriptions of problems that were not handled within the cooldown
     */
    List<String> collect() {
        Instant now = Instant.now();
        Duration cooldown = Duration.ofMinutes(this.config.autonomyCooldownMinutes);
        this.handled.values().removeIf(time -> Duration.between(time, now).compareTo(cooldown) > 0);
        Map<String, String> found = new LinkedHashMap<>();

        for (HealthCheck check : this.api.health().getChecks()) {
            List<HealthCheckFeedback> feedbacks;
            try {
                feedbacks = new ArrayList<>(check.getHealthCheckFeedbacks(HealthCheckLevel.ERROR));
            } catch (RuntimeException e) {
                continue;
            }
            for (HealthCheckFeedback feedback : feedbacks) {
                String text = "Health check " + check.getName() + " failed: " + feedback.getDescription();
                found.putIfAbsent(signature(text), text);
            }
        }

        Instant since = this.lastLogCheck;
        this.lastLogCheck = now;
        for (LogEntry entry : this.api.system().getRecentLog(500, 1000)) {
            if (!entry.time().isAfter(since) || !relevant(entry.message())) {
                continue;
            }
            String message = entry.message().trim();
            String text = "Error in STEM log: " + (message.length() > 400 ? message.substring(0, 400) + "..." : message);
            found.putIfAbsent(signature(message), text);
        }

        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> entry : found.entrySet()) {
            if (this.handled.containsKey(entry.getKey()) || problems.size() >= MAX_PROBLEMS) {
                continue;
            }
            this.handled.put(entry.getKey(), now);
            problems.add(entry.getValue());
        }
        return problems;
    }

    private boolean relevant(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String text = message.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        /* frame lines and stack frames of logged exceptions */
        if (text.startsWith("#####") || text.startsWith("at ")) {
            return false;
        }
        /* Mirra's own OpenAI problems are reported directly, never handled by Mirra herself */
        if (lower.contains("openai") || lower.contains("watchdog")) {
            return false;
        }
        for (String ignore : this.config.autonomyIgnore) {
            if (!ignore.isBlank() && lower.contains(ignore)) {
                return false;
            }
        }
        return true;
    }

    private void handle(List<String> problems) {
        Optional<IdentityUser> approver = this.plugin.getIdentityManager().findIdentity(this.config.approverName);
        Optional<UserToken> token = approver.flatMap(user -> this.plugin.getMessenger().tokenOf(user, null));
        if (token.isEmpty()) {
            this.plugin.getPluginLogger().warning("Watchdog: approver '" + this.config.approverName + "' not reachable, "
                    + problems.size() + " problem(s) not handled");
            return;
        }
        String name = approver.get().getIdentityName();
        StringBuilder message = new StringBuilder("(Automatic STEM watchdog, nobody asked you right now.)\n")
                .append("New problems in STEM:\n");
        problems.forEach(problem -> message.append("- ").append(problem).append('\n'));
        message.append('\n').append("Analyze them with your tools: health checks, stem_read_log, information blocks, devices, "
                + "console commands");
        if (approver.get().hasPermission(de.linzn.mirra.identitySystem.AiPermissions.SHELL)) {
            message.append(" and the shell");
        }
        message.append(".\n");
        if (this.config.autonomyMayFix) {
            message.append("If a fix is safe and reversible (run a check again, retry a backup or job, reconnect, restart a "
                    + "service or container), do it without asking and check the result. Never delete data, never shut down "
                    + "or reboot machines, never change configuration or permissions, never contact other people; suggest "
                    + "such steps to ").append(name).append(" instead.\n");
        } else {
            message.append("Do not change anything, only analyze.\n");
        }
        message.append("Then tell ").append(name).append(" briefly what happened, what you did and what is still open. ")
                .append("If everything is harmless or already resolved, answer exactly ").append(TaskManager.NO_REPORT).append('.');
        this.plugin.getPluginLogger().info("Watchdog: handling " + problems.size() + " problem(s)");
        ChatContext chat = this.plugin.chatContext(token.get(), name);
        String answer = this.plugin.getAgent().chatAutomatic(chat, message.toString());
        if (answer == null || answer.trim().toUpperCase(Locale.ROOT).startsWith(TaskManager.NO_REPORT)) {
            this.plugin.getPluginLogger().info("Watchdog: nothing to report");
            return;
        }
        this.plugin.deliverAnswer(chat, "🛠️ " + answer, false);
    }

    /**
     * Same problem, same signature: numbers, ids and times are replaced.
     */
    static String signature(String text) {
        String normalized = text.toLowerCase(Locale.ROOT).replaceAll("[0-9a-f]{8,}", "#").replaceAll("\\d+", "#").replaceAll("\\s+", " ").trim();
        return normalized.length() > 160 ? normalized.substring(0, 160) : normalized;
    }

    /**
     * @return information for the tools: runs today and the remembered problems
     */
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("mode", this.config.autonomyMayFix ? "fix" : "report");
        status.put("runs_today", this.runsToday.get());
        status.put("max_runs_per_day", this.config.autonomyMaxRunsPerDay);
        status.put("problems_in_cooldown", this.handled.size());
        return status;
    }
}
