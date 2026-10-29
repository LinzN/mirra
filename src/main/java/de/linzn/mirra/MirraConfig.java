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

package de.linzn.mirra;

import de.linzn.simplyConfiguration.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * All settings of Mirra, read once at startup. Missing keys are written with their default value.
 */
public final class MirraConfig {

    /* OpenAI */
    public final String openAIToken;
    public final String openAIBaseUrl;
    public final int openAITimeoutSeconds;

    /* model */
    public final String modelName;
    public final String textModel;
    public final String memoryModel;
    public final String embeddingModel;
    public final String imageModel;
    public final String reasoningEffort;
    public final String memoryReasoningEffort;
    public final String transcriptionModel;
    public final String personality;
    public final String defaultIdentityName;

    /* agent */
    public final int maxToolRounds;
    public final int historyMessages;
    public final boolean webSearch;
    public final List<String> blockedCommands;
    public final int commandTimeoutSeconds;
    public final int scriptTimeoutSeconds;

    /* memory */
    public final boolean attachmentsEnabled;
    public final boolean attachmentsForGuests;
    public final int attachmentMaxSizeMB;
    public final int attachmentKeepMinutes;

    public final boolean voiceEnabled;
    public final boolean voiceReplyToVoice;
    public final String voiceModel;
    public final String voiceName;
    public final String voiceInstructions;
    public final String voiceEngine;
    public final String transcriptionLanguage;
    public final String transcriptionPrompt;
    public final String voiceRealtimeModel;

    public final boolean shellEnabled;
    public final String shellSshUser;
    public final int shellSshPort;
    public final int shellTimeoutSeconds;
    public final List<String> shellAllowedHosts;

    public final boolean autoCreateGuests;
    public final boolean requireApproval;
    public final String approverName;
    public final String pendingMessage;
    public final String approvedMessage;
    public final String deniedMessage;

    public final String pausedMessage;

    public final boolean autonomyEnabled;
    public final boolean autonomyMayFix;
    public final int autonomyIntervalMinutes;
    public final int autonomyCooldownMinutes;
    public final int autonomyMaxRunsPerDay;
    public final List<String> autonomyIgnore;

    public final boolean autoMemory;
    public final int summaryThreshold;
    public final int memoryContextSize;

    /* social status */
    public final boolean socialStatusEnabled;
    public final int socialStatusMinDays;
    public final int socialStatusMaxDays;
    public final int socialStatusEarliestHour;
    public final int socialStatusLatestHour;

    public MirraConfig(FileConfiguration config) {
        this.openAIToken = string(config, "openAI.token", "xxxx");
        this.openAIBaseUrl = trimSlash(string(config, "openAI.baseUrl", "https://api.openai.com/v1"));
        this.openAITimeoutSeconds = Math.max(10, integer(config, "openAI.timeoutSeconds", 120));

        this.modelName = string(config, "default.model", "mirra");
        this.defaultIdentityName = string(config, "default.identityName", "Niklas");
        String prefix = "model." + this.modelName + ".";
        this.textModel = string(config, prefix + "textOpenAIModel", "gpt-4.1");
        this.imageModel = string(config, prefix + "imageOpenAIModel", "gpt-image-1");
        this.personality = string(config, prefix + "aiRoleDescription", "You are Mirra, a helpful smart home assistant.");
        this.memoryModel = string(config, prefix + "memoryModel", this.textModel);
        this.embeddingModel = string(config, prefix + "embeddingModel", "text-embedding-3-small");
        this.reasoningEffort = string(config, prefix + "reasoningEffort", "").trim().toLowerCase(Locale.ROOT);
        this.memoryReasoningEffort = string(config, prefix + "memoryReasoningEffort", "").trim().toLowerCase(Locale.ROOT);
        this.transcriptionModel = string(config, prefix + "transcriptionModel", "gpt-4o-mini-transcribe");

        this.maxToolRounds = clamp(integer(config, "agent.maxToolRounds", 10), 1, 30);
        this.historyMessages = clamp(integer(config, "agent.historyMessages", 16), 2, 100);
        this.webSearch = bool(config, "agent.webSearch", false);
        this.blockedCommands = stringList(config, "agent.blockedCommands", List.of("stop", "reboot"));
        this.commandTimeoutSeconds = clamp(integer(config, "agent.commandTimeoutSeconds", 60), 1, 600);
        this.scriptTimeoutSeconds = clamp(integer(config, "agent.scriptTimeoutSeconds", 120), 1, 3600);

        this.attachmentsEnabled = bool(config, "attachments.enabled", true);
        this.attachmentsForGuests = bool(config, "attachments.guests", false);
        this.attachmentMaxSizeMB = clamp(integer(config, "attachments.maxSizeMB", 25), 1, 50);
        this.attachmentKeepMinutes = clamp(integer(config, "attachments.keepMinutes", 30), 0, 1440);

        this.voiceEnabled = bool(config, "voice.enabled", true);
        this.voiceReplyToVoice = bool(config, "voice.replyToVoice", false);
        this.voiceModel = string(config, "voice.model", "gpt-4o-mini-tts");
        this.voiceName = string(config, "voice.voice", "nova");
        this.voiceInstructions = string(config, "voice.instructions", "Speak in German, young, cheerful and a bit cheeky.");
        /* realtime: gpt-realtime-* reads the text aloud; speech: /audio/speech (gpt-4o-mini-tts, ends 2027-01-06) */
        this.voiceEngine = string(config, "voice.engine", "realtime");
        /* speech to text: language and vocabulary hints, e.g. the name Mirra and smart home terms */
        this.transcriptionLanguage = string(config, "voice.transcriptionLanguage", "de");
        this.transcriptionPrompt = string(config, "voice.transcriptionPrompt",
                "Jemand spricht mit Mirra, der Smart-Home-Assistentin. Begriffe: Mirra, STEM, Licht, Lampe, Steckdose, "
                        + "Heizung, Klimaanlage, 3D-Drucker, Wetter, Erinnerung, Küche, Wohnzimmer, Schlafzimmer, Labor.");
        this.voiceRealtimeModel = string(config, "voice.realtimeModel", "gpt-realtime-2.1-mini");

        this.shellEnabled = bool(config, "agent.shell.enabled", true);
        this.shellSshUser = string(config, "agent.shell.sshUser", "root");
        this.shellSshPort = clamp(integer(config, "agent.shell.sshPort", 22), 1, 65535);
        this.shellTimeoutSeconds = clamp(integer(config, "agent.shell.timeoutSeconds", 120), 1, 3600);
        this.shellAllowedHosts = stringList(config, "agent.shell.allowedHosts", List.of());

        this.autoCreateGuests = bool(config, "users.autoCreateGuests", true);
        this.requireApproval = bool(config, "users.requireApproval", true);
        this.approverName = string(config, "users.approver", this.defaultIdentityName);
        this.pendingMessage = string(config, "users.pendingMessage",
                "Hi! 👋 Ich bin Mirra. Deine Anfrage wurde weitergeleitet und wartet auf Freigabe. Ich melde mich, sobald du freigeschaltet bist. (Request pending)");
        this.approvedMessage = string(config, "users.approvedMessage", "Du bist freigeschaltet ✅ Ab jetzt kannst du mit mir schreiben.");
        this.deniedMessage = string(config, "users.deniedMessage", "");

        this.pausedMessage = string(config, "openAI.pausedMessage",
                "Sorry, mein OpenAI-Guthaben ist gerade aufgebraucht 😕 Ich bin pausiert, bis wieder aufgeladen ist. Der Admin ist informiert.");

        this.autonomyEnabled = bool(config, "autonomy.enabled", true);
        this.autonomyMayFix = !"report".equalsIgnoreCase(string(config, "autonomy.mode", "fix").trim());
        this.autonomyIntervalMinutes = clamp(integer(config, "autonomy.intervalMinutes", 5), 1, 1440);
        this.autonomyCooldownMinutes = clamp(integer(config, "autonomy.cooldownMinutes", 360), 5, 10080);
        this.autonomyMaxRunsPerDay = clamp(integer(config, "autonomy.maxRunsPerDay", 12), 0, 500);
        this.autonomyIgnore = stringList(config, "autonomy.ignore", List.of());

        this.autoMemory = bool(config, "memory.autoExtract", true);
        this.summaryThreshold = clamp(integer(config, "memory.summaryThreshold", 40), 10, 500);
        this.memoryContextSize = clamp(integer(config, "memory.contextSize", 12), 0, 50);

        this.socialStatusEnabled = bool(config, "agent.socialStatus.enabled", true);
        this.socialStatusMinDays = clamp(integer(config, "agent.socialStatus.minDays", 4), 1, 60);
        this.socialStatusMaxDays = clamp(integer(config, "agent.socialStatus.maxDays", 10), this.socialStatusMinDays, 90);
        this.socialStatusEarliestHour = clamp(integer(config, "agent.socialStatus.earliestHour", 9), 0, 23);
        this.socialStatusLatestHour = clamp(integer(config, "agent.socialStatus.latestHour", 21), this.socialStatusEarliestHour, 23);
        config.save();
    }

    private static String trimSlash(String url) {
        String result = url.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /* the YAML provider returns numbers, booleans and strings as written, so values are converted here */
    static String string(FileConfiguration config, String path, String def) {
        Object value = config.get(path, def);
        return value == null ? def : String.valueOf(value);
    }

    static int integer(FileConfiguration config, String path, int def) {
        Object value = config.get(path, def);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static boolean bool(FileConfiguration config, String path, boolean def) {
        Object value = config.get(path, def);
        if (value instanceof Boolean b) {
            return b;
        }
        return value == null ? def : Boolean.parseBoolean(String.valueOf(value).trim());
    }

    static List<String> stringList(FileConfiguration config, String path, List<String> def) {
        Object value = config.get(path, new ArrayList<>(def));
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object entry : list) {
                if (entry != null) {
                    result.add(String.valueOf(entry).trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return List.copyOf(result);
    }
}
