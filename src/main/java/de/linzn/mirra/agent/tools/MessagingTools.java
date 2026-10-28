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

package de.linzn.mirra.agent.tools;

import de.linzn.mirra.MirraPlugin;
import de.linzn.mirra.agent.ChatContext;
import de.linzn.mirra.identitySystem.IdentityUser;
import de.linzn.mirra.identitySystem.UserToken;
import de.linzn.mirra.messaging.ChatMessenger;
import de.linzn.mirra.messaging.OutgoingFile;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static de.linzn.mirra.agent.tools.Schema.*;
import static de.linzn.mirra.agent.tools.SimpleTool.anyOf;
import static de.linzn.mirra.agent.tools.SimpleTool.notGuest;
import static de.linzn.mirra.identitySystem.AiPermissions.*;

/**
 * Tools to send messages, files and voice messages to the current chat and to other registered users.
 */
public final class MessagingTools {

    private static final long MAX_FILE_BYTES = 64L * 1024 * 1024;

    private MessagingTools() {
    }

    public static List<AgentTool> create(MirraPlugin plugin) {
        List<AgentTool> tools = new ArrayList<>();
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build();

        tools.add(new SimpleTool("send_message",
                "Send a text message to another registered user on Discord or WhatsApp. Only on explicit request of the person "
                        + "you talk to. Write the message so the receiver understands who it is from.",
                Schema.object()
                        .prop("recipient", string("name of the registered user, see list_users"))
                        .prop("text", string("the message"))
                        .prop("channel", nullable(enumeration("discord, whatsapp or voice (speak on a voice device); null for the user's default", List.of("discord", "whatsapp", "voice"))))
                        .build(),
                anyOf(ADMIN), (chat, args) -> {
            UserToken token = recipient(plugin, requireString(args, "recipient"), optString(args, "channel"));
            String text = requireString(args, "text");
            plugin.getMessenger().sendText(token, text);
            plugin.getAgent().recordOutgoing(token.getName(), text);
            plugin.getPluginLogger().info("Message to " + args.getString("recipient") + " on behalf of " + chat.name());
            return new JSONObject().put("sent", true).put("channel", token.getSource().name().toLowerCase());
        }));

        /* voice devices (smart speakers in the house) */
        tools.add(new SimpleTool("list_voice_devices",
                "List the voice devices (smart speakers in the house) with room name and whether they are online.",
                Schema.empty(), anyOf(HOME_VIEW), (chat, args) -> {
            JSONArray devices = new JSONArray();
            for (de.linzn.mirra.voice.MirraVoice.Device device : plugin.getVoice().devices()) {
                devices.put(new JSONObject().put("id", device.id()).put("name", device.name()).put("online", device.online()));
            }
            return new JSONObject().put("devices", devices);
        }));

        tools.add(new SimpleTool("announce",
                "Say something out loud on a voice device in the house, or on all of them. Only on request of the person "
                        + "or as part of a planned task.",
                Schema.object()
                        .prop("device", nullable(string("room name or id of the device; null for all online devices")))
                        .prop("text", string("what to say, short and in spoken language"))
                        .build(),
                anyOf(HOME_CONTROL), (chat, args) -> {
            String text = requireString(args, "text");
            String wanted = optString(args, "device");
            List<de.linzn.mirra.voice.MirraVoice.Device> targets = new ArrayList<>();
            if (wanted == null || wanted.isBlank()) {
                plugin.getVoice().devices().stream().filter(de.linzn.mirra.voice.MirraVoice.Device::online).forEach(targets::add);
            } else {
                targets.add(plugin.getVoice().find(wanted).orElseThrow(() -> new IllegalArgumentException("No voice device '" + wanted + "'")));
            }
            JSONArray done = new JSONArray();
            for (de.linzn.mirra.voice.MirraVoice.Device device : targets) {
                if (device.online()) {
                    plugin.getVoice().speak(device.id(), text);
                    done.put(device.name() == null ? device.id() : device.name());
                }
            }
            plugin.getPluginLogger().info("Announcement on " + done + " on behalf of " + chat.name());
            return new JSONObject().put("success", !done.isEmpty()).put("spoken_on", done);
        }));

        tools.add(new SimpleTool("send_file",
                "Send a file into the current chat, or to another registered user. The content comes from exactly one source: "
                        + "'content' for text files you write yourself (CSV, JSON, YAML, scripts, Markdown, logs, ICS, ...), "
                        + "'path' for a file on the STEM server, or 'url' for a file to download (e.g. a cloud share link).",
                Schema.object()
                        .prop("file_name", string("file name with extension, e.g. werte.csv"))
                        .prop("content", nullable(string("text content of the file")))
                        .prop("path", nullable(string("absolute path of a file on the STEM server (needs SHELL permission)")))
                        .prop("url", nullable(string("http(s) URL to download the file from")))
                        .prop("caption", nullable(string("short text sent with the file")))
                        .prop("recipient", nullable(string("registered user to send the file to; null for the current chat")))
                        .build(),
                notGuest(), (chat, args) -> {
            String fileName = requireString(args, "file_name");
            String content = optString(args, "content");
            String path = optString(args, "path");
            String url = optString(args, "url");
            int sources = (content != null ? 1 : 0) + (path != null ? 1 : 0) + (url != null ? 1 : 0);
            if (sources != 1) {
                throw new IllegalArgumentException("Give exactly one of content, path or url");
            }
            byte[] data;
            if (content != null) {
                data = content.getBytes(StandardCharsets.UTF_8);
            } else if (path != null) {
                if (!chat.has(SHELL)) {
                    throw new IllegalArgumentException("Reading files of the server needs the SHELL permission");
                }
                Path file = Path.of(path);
                if (!Files.isRegularFile(file)) {
                    throw new IllegalArgumentException("No such file: " + path);
                }
                if (Files.size(file) > MAX_FILE_BYTES) {
                    throw new IllegalArgumentException("File is larger than 64 MB");
                }
                data = Files.readAllBytes(file);
            } else {
                URI uri = URI.create(url);
                if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) {
                    throw new IllegalArgumentException("Only http and https URLs");
                }
                HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60)).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200) {
                    throw new IllegalArgumentException("Download failed with HTTP " + response.statusCode());
                }
                data = response.body();
            }
            UserToken token = target(plugin, chat, optString(args, "recipient"));
            plugin.getMessenger().sendFile(token, OutgoingFile.of(fileName, data), optString(args, "caption"));
            if (!token.equals(chat.userToken())) {
                plugin.getAgent().recordOutgoing(token.getName(), "📎 " + fileName);
            }
            return new JSONObject().put("sent", true).put("bytes", data.length);
        }));

        tools.add(new SimpleTool("send_voice_message",
                "Speak a text with Mirra's voice and send it as voice message into the current chat, or to another registered user. "
                        + "Use it when somebody asks for a voice message. The voice message replaces a text answer, so afterwards "
                        + "answer only with a very short text or nothing important.",
                Schema.object()
                        .prop("text", string("what to say, written as spoken language without emojis or markdown"))
                        .prop("recipient", nullable(string("registered user to send it to; null for the current chat")))
                        .build(),
                notGuest(), (chat, args) -> {
            UserToken token = target(plugin, chat, optString(args, "recipient"));
            String text = requireString(args, "text");
            plugin.getMessenger().sendVoice(token, text);
            if (!token.equals(chat.userToken())) {
                plugin.getAgent().recordOutgoing(token.getName(), "🎤 " + text);
            }
            return new JSONObject().put("sent", true);
        }));
        return tools;
    }

    private static UserToken recipient(MirraPlugin plugin, String name, String channel) {
        IdentityUser identity = plugin.getIdentityManager().findIdentity(name)
                .orElseThrow(() -> new IllegalArgumentException("Unknown user '" + name + "', see list_users"));
        ChatMessenger messenger = plugin.getMessenger();
        return messenger.tokenOf(identity, channel)
                .orElseThrow(() -> new IllegalArgumentException(identity.getIdentityName() + " cannot be reached"
                        + (channel == null ? "" : " on " + channel)));
    }

    /**
     * The current chat, or another user if the sender may message others.
     */
    private static UserToken target(MirraPlugin plugin, ChatContext chat, String recipient) {
        if (recipient == null || recipient.isBlank()) {
            return chat.userToken();
        }
        if (!chat.has(ADMIN)) {
            throw new IllegalArgumentException("Sending to other users needs the ADMIN permission");
        }
        return recipient(plugin, recipient, null);
    }
}
