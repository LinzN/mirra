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

    private JSONObject post(String path, JSONObject body) throws OpenAIException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(this.baseUrl + path))
                .timeout(this.timeout)
                .header("Authorization", "Bearer " + this.apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
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
                return new JSONObject(response.body());
            }
            lastError = new OpenAIException(status, errorMessage(response.body()));
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
