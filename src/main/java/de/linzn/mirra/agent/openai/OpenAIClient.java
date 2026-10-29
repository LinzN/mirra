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

package de.linzn.mirra.agent.openai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Minimal client for the OpenAI REST API: Responses (agents with tools), Embeddings and Images.
 * Uses only the JDK HTTP client and org.json. Rate limits and server errors are retried twice.
 */
public class OpenAIClient {

    private static final int MAX_RETRIES = 2;

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final Duration timeout;

    /**
     * @param baseUrl        e.g. {@code https://api.openai.com/v1}
     * @param apiKey         secret API key
     * @param timeoutSeconds timeout of a single request; reasoning models can take a while
     */
    public OpenAIClient(String baseUrl, String apiKey, int timeoutSeconds) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * POST /responses
     *
     * @param request request body (model, instructions, input, tools, previous_response_id, ...)
     */
    /**
     * Receives errors that need a person (no credits left, invalid key) and the recovery after credits ran out.
     */
    public interface ErrorListener {
        void onError(OpenAIException error);

        default void onRecovered() {
        }
    }

    /* after the credits ran out, requests are blocked and only one probe request is sent per interval */
    private static final java.time.Duration PROBE_INTERVAL = java.time.Duration.ofMinutes(15);
    private volatile ErrorListener errorListener;
    private volatile java.time.Instant pausedSince;
    private volatile java.time.Instant nextProbe;

    public void setErrorListener(ErrorListener errorListener) {
        this.errorListener = errorListener;
    }

    /**
     * @return true while OpenAI reported that the credits are used up; requests fail without being sent
     */
    public boolean isPaused() {
        return this.pausedSince != null;
    }

    /**
     * Throws instead of sending while paused; lets one probe request through per interval.
     */
    private synchronized void checkPaused() throws OpenAIException {
        if (this.pausedSince == null) {
            return;
        }
        java.time.Instant now = java.time.Instant.now();
        if (this.nextProbe != null && now.isBefore(this.nextProbe)) {
            throw new OpenAIException(429, "Mirra is paused, the OpenAI credits are used up", "insufficient_quota");
        }
        this.nextProbe = now.plus(PROBE_INTERVAL);
    }

    private void onSuccess() {
        if (this.pausedSince == null) {
            return;
        }
        synchronized (this) {
            if (this.pausedSince == null) {
                return;
            }
            this.pausedSince = null;
            this.nextProbe = null;
        }
        ErrorListener listener = this.errorListener;
        if (listener != null) {
            try {
                listener.onRecovered();
            } catch (RuntimeException ignored) {
                /* alerting must never break a request */
            }
        }
    }

    private void notifyError(OpenAIException error) {
        boolean first = false;
        if (error.isQuotaExceeded()) {
            synchronized (this) {
                first = this.pausedSince == null;
                if (first) {
                    this.pausedSince = java.time.Instant.now();
                }
                this.nextProbe = java.time.Instant.now().plus(PROBE_INTERVAL);
            }
        }
        ErrorListener listener = this.errorListener;
        if (listener != null && (first || error.isAuthError())) {
            try {
                listener.onError(error);
            } catch (RuntimeException ignored) {
                /* alerting must never hide the original error */
            }
        }
    }

    public AgentResponse createResponse(JSONObject request) throws OpenAIException, InterruptedException {
        JSONObject json = this.post("/responses", request);
        AgentResponse response = AgentResponse.parse(json);
        if ("failed".equals(response.status())) {
            throw new OpenAIException(500, "Response failed: " + response.errorMessage());
        }
        return response;
    }

    /**
     * POST /embeddings
     *
     * @return one vector per input, in input order
     */
    public List<float[]> embed(String model, List<String> inputs) throws OpenAIException, InterruptedException {
        if (inputs.isEmpty()) {
            return List.of();
        }
        JSONObject request = new JSONObject();
        request.put("model", model);
        request.put("input", new JSONArray(inputs));
        JSONObject json = this.post("/embeddings", request);
        JSONArray data = json.optJSONArray("data");
        if (data == null || data.length() != inputs.size()) {
            throw new OpenAIException(500, "Embedding response does not match the input");
        }
        float[][] vectors = new float[inputs.size()][];
        for (int i = 0; i < data.length(); i++) {
            JSONObject entry = data.getJSONObject(i);
            JSONArray embedding = entry.getJSONArray("embedding");
            float[] vector = new float[embedding.length()];
            for (int j = 0; j < vector.length; j++) {
                vector[j] = (float) embedding.getDouble(j);
            }
            vectors[entry.optInt("index", i)] = vector;
        }
        List<float[]> result = new ArrayList<>();
        for (float[] vector : vectors) {
            if (vector == null) {
                throw new OpenAIException(500, "Embedding response is incomplete");
            }
            result.add(vector);
        }
        return result;
    }

    /**
     * POST /images/generations
     *
     * @return the first image: either a URL or base64 data, depending on the model
     */
    public GeneratedImage generateImage(String model, String prompt) throws OpenAIException, InterruptedException {
        JSONObject request = new JSONObject();
        request.put("model", model);
        request.put("prompt", prompt);
        request.put("n", 1);
        JSONObject json = this.post("/images/generations", request);
        JSONArray data = json.optJSONArray("data");
        if (data == null || data.isEmpty()) {
            throw new OpenAIException(500, "No image returned");
        }
        JSONObject image = data.getJSONObject(0);
        return new GeneratedImage(Optional.ofNullable(image.optString("url", null)), Optional.ofNullable(image.optString("b64_json", null)));
    }

    /**
     * Downloads a file, e.g. a generated image.
     */
    public byte[] download(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build();
        HttpResponse<byte[]> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("Download failed with HTTP " + response.statusCode());
        }
        return response.body();
    }

    /**
     * POST /audio/transcriptions
     *
     * @param fileName name with an extension OpenAI knows (mp3, m4a, ogg, wav, webm, ...)
     * @return the transcribed text
     */
    public String transcribe(String model, String fileName, String mimeType, byte[] data) throws OpenAIException, InterruptedException {
        String boundary = "----mirra" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeField(body, boundary, "model", model);
        writeField(body, boundary, "response_format", "json");
        String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName.replace("\"", "") + "\"\r\n"
                + "Content-Type: " + (mimeType == null ? "application/octet-stream" : mimeType) + "\r\n\r\n";
        body.writeBytes(header.getBytes(StandardCharsets.UTF_8));
        body.writeBytes(data);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(this.baseUrl + "/audio/transcriptions"))
                .timeout(this.timeout)
                .header("Authorization", "Bearer " + this.apiKey)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        return this.execute(request).optString("text", "");
    }

    /**
     * POST /audio/speech
     *
     * @param format opus, mp3, aac, flac, wav or pcm
     * @return the audio
     */
    public byte[] speech(String model, String voice, String instructions, String text, String format) throws OpenAIException, InterruptedException {
        JSONObject body = new JSONObject().put("model", model).put("voice", voice).put("input", text).put("response_format", format);
        if (instructions != null && !instructions.isBlank()) {
            body.put("instructions", instructions);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(this.baseUrl + "/audio/speech"))
                .timeout(this.timeout)
                .header("Authorization", "Bearer " + this.apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        this.checkPaused();
        HttpResponse<byte[]> response;
        try {
            response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new OpenAIException(0, "Connection to OpenAI failed: " + e.getMessage());
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String errorBody = new String(response.body(), StandardCharsets.UTF_8);
            OpenAIException error = new OpenAIException(response.statusCode(), errorMessage(errorBody), errorCode(errorBody));
            if (error.isQuotaExceeded() || error.isAuthError()) {
                this.notifyError(error);
            }
            throw error;
        }
        this.onSuccess();
        return response.body();
    }

    /**
     * Speech with a Realtime model over a WebSocket (wss://.../realtime). The model gets the text as a user
     * message and the instruction to read it aloud word for word.
     *
     * @return PCM 16 bit little endian mono with 24 kHz, and the transcript of what the model actually said
     */
    public RealtimeSpeech realtimeSpeech(String model, String voice, String instructions, String text)
            throws OpenAIException, InterruptedException {
        this.checkPaused();
        String wsBase = this.baseUrl.replaceFirst("^http", "ws");
        URI uri = URI.create(wsBase + "/realtime?model=" + java.net.URLEncoder.encode(model, StandardCharsets.UTF_8));
        ByteArrayOutputStream audio = new ByteArrayOutputStream();
        StringBuilder transcript = new StringBuilder();
        java.util.concurrent.CompletableFuture<Void> done = new java.util.concurrent.CompletableFuture<>();

        java.net.http.WebSocket.Listener listener = new java.net.http.WebSocket.Listener() {
            private final StringBuilder frame = new StringBuilder();

            @Override
            public java.util.concurrent.CompletionStage<?> onText(java.net.http.WebSocket webSocket, CharSequence data, boolean last) {
                this.frame.append(data);
                if (last) {
                    String message = this.frame.toString();
                    this.frame.setLength(0);
                    try {
                        handle(webSocket, new JSONObject(message));
                    } catch (Exception e) {
                        done.completeExceptionally(e);
                    }
                }
                webSocket.request(1);
                return null;
            }

            private void handle(java.net.http.WebSocket webSocket, JSONObject event) {
                switch (event.optString("type")) {
                    case "response.output_audio.delta", "response.audio.delta" ->
                            audio.writeBytes(java.util.Base64.getDecoder().decode(event.optString("delta", "")));
                    case "response.output_audio_transcript.delta", "response.audio_transcript.delta" ->
                            transcript.append(event.optString("delta", ""));
                    case "response.done" -> {
                        JSONObject response = event.optJSONObject("response");
                        String status = response == null ? "completed" : response.optString("status", "completed");
                        if (!"completed".equals(status)) {
                            JSONObject details = response.optJSONObject("status_details");
                            JSONObject error = details == null ? null : details.optJSONObject("error");
                            done.completeExceptionally(new OpenAIException(0, "Realtime response " + status
                                    + (error != null ? ": " + error.optString("message") : ""),
                                    error != null ? error.optString("code", null) : null));
                        } else {
                            done.complete(null);
                        }
                    }
                    case "error" -> {
                        JSONObject error = event.optJSONObject("error");
                        done.completeExceptionally(new OpenAIException(0,
                                error == null ? "Realtime error" : error.optString("message", "Realtime error"),
                                error == null ? null : error.optString("code", null)));
                    }
                    default -> {
                    }
                }
            }

            @Override
            public void onError(java.net.http.WebSocket webSocket, Throwable error) {
                done.completeExceptionally(error);
            }

            @Override
            public java.util.concurrent.CompletionStage<?> onClose(java.net.http.WebSocket webSocket, int statusCode, String reason) {
                done.completeExceptionally(new OpenAIException(statusCode, "Realtime connection closed: " + reason));
                return null;
            }
        };

        java.net.http.WebSocket socket;
        try {
            socket = this.httpClient.newWebSocketBuilder()
                    .header("Authorization", "Bearer " + this.apiKey)
                    .connectTimeout(Duration.ofSeconds(15))
                    .buildAsync(uri, listener)
                    .get(20, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof java.net.http.WebSocketHandshakeException handshake) {
                int code = handshake.getResponse().statusCode();
                Object body = handshake.getResponse().body();
                String bodyText = body instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(body);
                OpenAIException error = new OpenAIException(code, errorMessage(bodyText), errorCode(bodyText));
                if (error.isQuotaExceeded() || error.isAuthError()) {
                    this.notifyError(error);
                }
                throw error;
            }
            throw new OpenAIException(0, "Realtime connection failed: " + cause.getMessage());
        }
        try {
            JSONObject session = new JSONObject()
                    .put("type", "realtime")
                    .put("output_modalities", new JSONArray().put("audio"))
                    .put("instructions", instructions)
                    .put("audio", new JSONObject().put("output", new JSONObject()
                            .put("format", new JSONObject().put("type", "audio/pcm").put("rate", 24000))
                            .put("voice", voice)));
            socket.sendText(new JSONObject().put("type", "session.update").put("session", session).toString(), true).join();
            JSONObject item = new JSONObject().put("type", "message").put("role", "user")
                    .put("content", new JSONArray().put(new JSONObject().put("type", "input_text").put("text", text)));
            socket.sendText(new JSONObject().put("type", "conversation.item.create").put("item", item).toString(), true).join();
            socket.sendText(new JSONObject().put("type", "response.create").toString(), true).join();
            done.get(this.timeout.toSeconds(), java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof OpenAIException error) {
                if (error.isQuotaExceeded() || error.isAuthError()) {
                    this.notifyError(error);
                }
                throw error;
            }
            throw new OpenAIException(0, "Realtime speech failed: " + (cause == null ? e.getMessage() : cause.getMessage()));
        } catch (java.util.concurrent.TimeoutException e) {
            throw new OpenAIException(0, "Realtime speech timed out");
        } finally {
            socket.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "done");
        }
        this.onSuccess();
        return new RealtimeSpeech(audio.toByteArray(), transcript.toString());
    }

    /**
     * @param pcm        PCM 16 bit little endian mono, 24 kHz
     * @param transcript what the model said
     */
    public record RealtimeSpeech(byte[] pcm, String transcript) {
    }

    private static void writeField(ByteArrayOutputStream body, String boundary, String name, String value) {
        String part = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n";
        body.writeBytes(part.getBytes(StandardCharsets.UTF_8));
    }

    private JSONObject post(String path, JSONObject body) throws OpenAIException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(this.baseUrl + path))
                .timeout(this.timeout)
                .header("Authorization", "Bearer " + this.apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        return this.execute(request);
    }

    private JSONObject execute(HttpRequest request) throws OpenAIException, InterruptedException {
        this.checkPaused();
        OpenAIException lastError = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                Thread.sleep(retryDelayMillis(attempt, lastError));
            }
            HttpResponse<String> response;
            try {
                response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (IOException e) {
                lastError = new OpenAIException(0, "Connection to OpenAI failed: " + e.getMessage());
                continue;
            }
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                this.onSuccess();
                return new JSONObject(response.body());
            }
            lastError = new OpenAIException(status, errorMessage(response.body()), errorCode(response.body()));
            if (lastError.isQuotaExceeded() || lastError.isAuthError()) {
                this.notifyError(lastError);
                throw lastError;
            }
            lastError.retryAfterSeconds = response.headers().firstValue("retry-after").map(OpenAIClient::parseSeconds).orElse(-1);
            if (!isRetryable(status)) {
                throw lastError;
            }
        }
        throw lastError;
    }

    private static boolean isRetryable(int status) {
        return status == 408 || status == 409 || status == 429 || status >= 500;
    }

    private static long retryDelayMillis(int attempt, OpenAIException lastError) {
        if (lastError != null && lastError.retryAfterSeconds > 0 && lastError.retryAfterSeconds <= 30) {
            return lastError.retryAfterSeconds * 1000L;
        }
        return attempt == 1 ? 2000 : 6000;
    }

    private static int parseSeconds(String value) {
        try {
            return (int) Math.ceil(Double.parseDouble(value.trim()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String errorCode(String body) {
        try {
            JSONObject error = new JSONObject(body).optJSONObject("error");
            if (error != null) {
                String code = error.optString("code", "");
                return code.isEmpty() ? error.optString("type", null) : code;
            }
        } catch (Exception ignored) {
            /* not JSON */
        }
        return null;
    }

    private static String errorMessage(String body) {
        try {
            JSONObject error = new JSONObject(body).optJSONObject("error");
            if (error != null) {
                return error.optString("message", body);
            }
        } catch (Exception ignored) {
            /* not JSON */
        }
        return body == null || body.length() <= 300 ? body : body.substring(0, 300);
    }

    /**
     * @param url    link to the image (DALL·E models)
     * @param base64 image data (gpt-image models)
     */
    public record GeneratedImage(Optional<String> url, Optional<String> base64) {
    }
}
