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


package de.linzn.mirra.voice;

import de.linzn.mirra.MirraConfig;
import de.linzn.mirra.MirraPlugin;
import de.linzn.mirra.agent.ChatContext;
import de.linzn.mirra.agent.openai.OpenAIClient;
import de.linzn.mirra.identitySystem.IdentityUser;
import de.linzn.mirra.identitySystem.TokenSource;
import de.linzn.mirra.identitySystem.UserToken;
import de.linzn.mirra.messaging.MessengerException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Speech interface of Mirra for voice devices (smart speakers). A device plugin registers a {@link Speaker}
 * and hands recorded speech to {@link #handle}. Each device is a chat channel of the source VOICE with the
 * device id as address, so new devices go through the normal guest approval and can be linked to a profile.
 */
public final class MirraVoice {

    /**
     * Sample rate of {@link #synthesize}: PCM 16 bit little endian mono.
     */
    public static final int SPEECH_SAMPLE_RATE = 24000;

    private final MirraPlugin plugin;
    private final MirraConfig config;
    private final OpenAIClient client;
    private final List<Speaker> speakers = new CopyOnWriteArrayList<>();

    public MirraVoice(MirraPlugin plugin, MirraConfig config, OpenAIClient client) {
        this.plugin = plugin;
        this.config = config;
        this.client = client;
    }

    /**
     * A plugin that drives voice devices.
     */
    public interface Speaker {
        /**
         * @return the devices this speaker knows, online or not
         */
        List<Device> devices();

        /**
         * Say something on a device outside of a conversation (announcement, reminder, message).
         *
         * @param text the spoken text
         */
        void say(String deviceId, String text) throws Exception;
    }

    public record Device(String id, String name, boolean online) {
    }

    /**
     * Result of a spoken turn.
     *
     * @param state      ANSWER, PENDING (device waits for approval), DENIED, EMPTY (nothing understood), PAUSED, ERROR
     * @param transcript what was understood
     * @param answer     the text to speak, may be empty
     */
    public record Answer(String state, String transcript, String answer) {
        public boolean hasAnswer() {
            return answer != null && !answer.isBlank();
        }
    }

    public void registerSpeaker(Speaker speaker) {
        this.speakers.add(speaker);
    }

    public void unregisterSpeaker(Speaker speaker) {
        this.speakers.remove(speaker);
    }

    public List<Device> devices() {
        List<Device> devices = new ArrayList<>();
        for (Speaker speaker : this.speakers) {
            try {
                devices.addAll(speaker.devices());
            } catch (Exception e) {
                this.plugin.getPluginLogger().warning("Voice speaker failed to list devices: " + e.getMessage());
            }
        }
        return devices;
    }

    private Optional<Speaker> speakerOf(String deviceId) {
        for (Speaker speaker : this.speakers) {
            for (Device device : speaker.devices()) {
                if (device.id().equalsIgnoreCase(deviceId)) {
                    return Optional.of(speaker);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Find a device by id or room name.
     */
    public Optional<Device> find(String idOrName) {
        if (idOrName == null) {
            return Optional.empty();
        }
        String wanted = idOrName.trim();
        return this.devices().stream().filter(device -> device.id().equalsIgnoreCase(wanted)
                || (device.name() != null && device.name().equalsIgnoreCase(wanted))).findFirst();
    }

    /**
     * @return true if the device is connected right now
     */
    public boolean canSpeak(String deviceId) {
        return this.devices().stream().anyMatch(device -> device.id().equalsIgnoreCase(deviceId) && device.online());
    }

    /**
     * Speak a text on a device, used for messages, reminders and announcements.
     */
    public void speak(String deviceId, String text) throws MessengerException {
        Speaker speaker = this.speakerOf(deviceId).orElseThrow(() -> new MessengerException("Unknown voice device " + deviceId));
        try {
            speaker.say(deviceId, text);
        } catch (Exception e) {
            throw new MessengerException("Speaking on " + deviceId + " failed: " + e.getMessage());
        }
    }

    /**
     * Text to speech with the voice of Mirra.
     *
     * @return PCM 16 bit little endian mono with {@link #SPEECH_SAMPLE_RATE}
     */
    public byte[] synthesize(String text) throws Exception {
        return this.plugin.getSpeech().pcm(text);
    }

    /**
     * Approval state of a device without creating a profile: "idle" (may talk, or unknown so far),
     * "pending" or "denied".
     */
    public String accessState(String deviceId) {
        if (!this.config.requireApproval) {
            return "idle";
        }
        for (IdentityUser user : this.plugin.getIdentityManager().getIdentities()) {
            for (UserToken token : user.getUserTokens()) {
                if (token.getSource() == TokenSource.VOICE && token.getName().equalsIgnoreCase(deviceId)) {
                    return switch (user.getAccess()) {
                        case APPROVED -> "idle";
                        case PENDING -> "pending";
                        case DENIED -> "denied";
                    };
                }
            }
        }
        return "idle";
    }

    /**
     * A spoken turn: understand the recording, check access and let Mirra answer.
     *
     * @param deviceId   id of the device, the address of the VOICE channel
     * @param deviceName room name of the device, shown to the owner and Mirra
     * @param wav        the recording as WAV file
     */
    public Answer handle(String deviceId, String deviceName, byte[] wav) {
        UserToken token = this.plugin.getIdentityManager().resolve(deviceId, TokenSource.VOICE, deviceName);
        ChatContext chat = this.plugin.chatContext(token, deviceName == null || deviceName.isBlank() ? deviceId : deviceName);
        IdentityUser user = chat.identityUser();
        if (this.config.requireApproval && user != null && user.getAccess() == IdentityUser.Access.DENIED) {
            return new Answer("DENIED", "", "");
        }
        if (this.client.isPaused()) {
            return new Answer("PAUSED", "", this.config.pausedMessage);
        }
        String transcript;
        try {
            transcript = this.client.transcribe(this.config.transcriptionModel, "speech.wav", "audio/wav", wav,
                    this.config.transcriptionLanguage, this.config.transcriptionPrompt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Answer("ERROR", "", "");
        } catch (Exception e) {
            this.plugin.getPluginLogger().warning("Transcription for " + deviceId + " failed: " + e.getMessage());
            return new Answer("ERROR", "", "");
        }
        transcript = transcript == null ? "" : transcript.trim();
        if (transcript.isEmpty() || isNoise(transcript)) {
            return new Answer("EMPTY", transcript, "");
        }
        this.plugin.getPluginLogger().info("Voice from " + chat.name() + ": " + transcript);
        if (!this.plugin.getAccessControl().allow(chat, transcript)) {
            return new Answer(user != null && user.getAccess() == IdentityUser.Access.DENIED ? "DENIED" : "PENDING", transcript, "");
        }
        String answer = this.plugin.getAgent().chat(chat, transcript);
        return new Answer("ANSWER", transcript, answer == null ? "" : answer);
    }

    /**
     * Whisper style models answer silence or noise with typical filler texts.
     */
    private static boolean isNoise(String transcript) {
        String text = transcript.toLowerCase().replaceAll("[^\\p{L}\\p{N} ]", "").trim();
        return text.isEmpty() || text.equals("untertitel im auftrag des zdf") || text.startsWith("untertitel der amaraorg")
                || text.equals("vielen dank") || text.equals("danke fürs zuschauen") || text.equals("tschüss");
    }
}
