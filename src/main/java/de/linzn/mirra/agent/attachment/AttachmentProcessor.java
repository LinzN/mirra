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

package de.linzn.mirra.agent.attachment;

import de.linzn.mirra.agent.openai.OpenAIClient;
import de.linzn.stem.api.StemLogger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Turns attachments into input for the Responses API:
 * <ul>
 *     <li>images as {@code input_image}</li>
 *     <li>documents (PDF, Office, OpenDocument) as {@code input_file}</li>
 *     <li>text, code, CSV, JSON, logs as text</li>
 *     <li>audio and voice messages as transcript</li>
 * </ul>
 */
public class AttachmentProcessor {

    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp", "image/gif");
    private static final Map<String, String> IMAGE_EXTENSIONS = Map.of(
            "jpg", "image/jpeg", "jpeg", "image/jpeg", "png", "image/png", "webp", "image/webp", "gif", "image/gif");

    /* documents OpenAI reads itself */
    private static final Map<String, String> DOCUMENTS = Map.ofEntries(
            Map.entry("pdf", "application/pdf"),
            Map.entry("doc", "application/msword"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("odt", "application/vnd.oasis.opendocument.text"),
            Map.entry("rtf", "application/rtf"),
            Map.entry("ppt", "application/vnd.ms-powerpoint"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            Map.entry("odp", "application/vnd.oasis.opendocument.presentation"),
            Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("ods", "application/vnd.oasis.opendocument.spreadsheet"));

    private static final Set<String> AUDIO_EXTENSIONS = Set.of("mp3", "mpga", "mpeg", "m4a", "mp4", "ogg", "oga", "opus", "wav", "webm", "flac", "aac");

    private static final int MAX_TEXT_CHARS = 60_000;

    private final OpenAIClient client;
    private final StemLogger logger;
    private final String transcriptionModel;
    private final long maxBytes;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    /**
     * Result for one message.
     *
     * @param parts       content items for the model: images, files and texts
     * @param reusable    the image and file items, kept for follow-up questions
     * @param historyNote short description for the chat history and the memory, including transcripts
     */
    public record Prepared(JSONArray parts, JSONArray reusable, String historyNote) {
        public static final Prepared EMPTY = new Prepared(new JSONArray(), new JSONArray(), "");

        public boolean isEmpty() {
            return this.parts.isEmpty() && this.historyNote.isEmpty();
        }
    }

    private volatile String transcriptionLanguage;
    private volatile String transcriptionPrompt;

    /**
     * Language and vocabulary hints for the transcription of voice messages.
     */
    public void setTranscriptionHints(String language, String prompt) {
        this.transcriptionLanguage = language;
        this.transcriptionPrompt = prompt;
    }

    public AttachmentProcessor(OpenAIClient client, StemLogger logger, String transcriptionModel, int maxSizeMB) {
        this.client = client;
        this.logger = logger;
        this.transcriptionModel = transcriptionModel;
        this.maxBytes = maxSizeMB * 1024L * 1024L;
    }

    public long maxBytes() {
        return this.maxBytes;
    }

    /**
     * Download a file, e.g. a Discord attachment.
     *
     * @throws IOException if the download fails or the file is larger than the limit
     */
    public byte[] download(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build();
        HttpResponse<InputStream> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("Download failed with HTTP " + response.statusCode());
            }
            byte[] data = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, this.maxBytes + 1));
            if (data.length > this.maxBytes) {
                throw new IOException("File is larger than " + (this.maxBytes / 1024 / 1024) + " MB");
            }
            return data;
        }
    }

    /**
     * Prepare all attachments of one message. Problems with single files are reported to the model as text,
     * so Mirra can tell the person what went wrong.
     */
    public Prepared prepare(List<Attachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return Prepared.EMPTY;
        }
        JSONArray parts = new JSONArray();
        JSONArray reusable = new JSONArray();
        List<String> notes = new ArrayList<>();
        for (Attachment attachment : attachments) {
            String name = attachment.fileName();
            try {
                if (attachment.size() == 0) {
                    String text = "[File " + name + " could not be loaded: empty, larger than " + (this.maxBytes / 1024 / 1024) + " MB or download failed]";
                    parts.put(text(text));
                    notes.add(text);
                    continue;
                }
                if (attachment.size() > this.maxBytes) {
                    String text = "[File " + name + " was not read: larger than " + (this.maxBytes / 1024 / 1024) + " MB]";
                    parts.put(text(text));
                    notes.add(text);
                    continue;
                }
                String extension = attachment.extension();
                String mime = attachment.mimeType();
                if (isImage(mime, extension)) {
                    String type = mime != null && IMAGE_TYPES.contains(mime) ? mime : IMAGE_EXTENSIONS.get(extension);
                    JSONObject image = new JSONObject().put("type", "input_image")
                            .put("image_url", dataUrl(type, attachment.data())).put("detail", "auto");
                    parts.put(text("[Image " + name + "]"));
                    parts.put(image);
                    reusable.put(text("[Image " + name + "]")).put(image);
                    notes.add("[Image: " + name + "]");
                } else if (attachment.voice() || isAudio(mime, extension)) {
                    String transcript = this.transcribe(attachment);
                    String label = attachment.voice() ? "Voice message" : "Audio " + name;
                    String text = transcript.isBlank()
                            ? "[" + label + ": no speech recognized]"
                            : "[" + label + ", transcript]: " + transcript;
                    parts.put(text(text));
                    notes.add(text);
                } else if (mime != null && mime.startsWith("video/")) {
                    String text = "[Video " + name + " received; videos cannot be read, only images, documents, text files and audio]";
                    parts.put(text(text));
                    notes.add("[Video: " + name + "]");
                } else if (DOCUMENTS.containsKey(extension)) {
                    JSONObject file = new JSONObject().put("type", "input_file").put("filename", name)
                            .put("file_data", dataUrl(DOCUMENTS.get(extension), attachment.data()));
                    parts.put(file);
                    reusable.put(file);
                    notes.add("[File: " + name + "]");
                } else {
                    Optional<String> content = decodeText(attachment.data());
                    if (content.isPresent()) {
                        String body = content.get();
                        boolean truncated = body.length() > MAX_TEXT_CHARS;
                        if (truncated) {
                            body = body.substring(0, MAX_TEXT_CHARS);
                        }
                        JSONObject text = text("[File " + name + (truncated ? ", truncated" : "") + "]\n" + body + "\n[End of " + name + "]");
                        parts.put(text);
                        reusable.put(text);
                        notes.add("[File: " + name + "]");
                    } else {
                        String text = "[File " + name + " (" + (mime == null ? extension : mime) + ") cannot be read; supported are images, "
                                + "PDF, Office and OpenDocument files, text and code files and audio]";
                        parts.put(text(text));
                        notes.add("[File: " + name + ", not readable]");
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                this.logger.warning("Could not read attachment " + name + ": " + e.getMessage());
                String text = "[File " + name + " could not be read: " + e.getMessage() + "]";
                parts.put(text(text));
                notes.add(text);
            }
        }
        return new Prepared(parts, reusable, String.join("\n", notes));
    }

    private String transcribe(Attachment attachment) throws Exception {
        String extension = attachment.extension();
        /* WhatsApp voice messages are ogg/opus; OpenAI wants a known extension */
        String fileName = attachment.fileName();
        if (extension.equals("opus") || extension.equals("oga") || !AUDIO_EXTENSIONS.contains(extension)) {
            fileName = "audio." + (attachment.mimeType() != null && attachment.mimeType().contains("mpeg") ? "mp3"
                    : attachment.mimeType() != null && attachment.mimeType().contains("mp4") ? "m4a" : "ogg");
        }
        return this.client.transcribe(this.transcriptionModel, fileName, attachment.mimeType(), attachment.data(),
                this.transcriptionLanguage, this.transcriptionPrompt).trim();
    }

    private static boolean isImage(String mime, String extension) {
        if (mime != null && IMAGE_TYPES.contains(mime)) {
            return true;
        }
        boolean genericType = mime == null || mime.startsWith("image/") || mime.equals("application/octet-stream");
        return genericType && IMAGE_EXTENSIONS.containsKey(extension);
    }

    private static boolean isAudio(String mime, String extension) {
        return (mime != null && mime.startsWith("audio/")) || (mime == null && AUDIO_EXTENSIONS.contains(extension) && !extension.equals("mp4"));
    }

    private static JSONObject text(String text) {
        return new JSONObject().put("type", "input_text").put("text", text);
    }

    private static String dataUrl(String mime, byte[] data) {
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(data);
    }

    /**
     * @return the content if it is UTF-8 text without binary control characters
     */
    static Optional<String> decodeText(byte[] data) {
        if (data == null || data.length == 0) {
            return Optional.empty();
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data)).toString();
            long control = text.chars().limit(10_000).filter(c -> c < 32 && c != '\n' && c != '\r' && c != '\t' && c != '\f').count();
            if (control > 0) {
                return Optional.empty();
            }
            return Optional.of(text.startsWith("\uFEFF") ? text.substring(1) : text);
        } catch (CharacterCodingException e) {
            return Optional.empty();
        }
    }
}
