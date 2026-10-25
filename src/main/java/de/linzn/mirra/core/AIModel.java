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

package de.linzn.mirra.core;

import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.ai.openai.models.*;
import com.azure.core.credential.KeyCredential;
import de.linzn.mirra.MirraPlugin;
import de.linzn.mirra.identitySystem.IdentityGuest;
import de.linzn.mirra.identitySystem.IdentityUser;
import de.linzn.mirra.identitySystem.UserToken;
import de.linzn.mirra.openai.ChatMessage;
import de.linzn.mirra.openai.IFunctionCall;
import de.linzn.stem.api.cloud.CloudFileHandle;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AIModel {

    /* a model answering with function calls forever must not block a thread forever */
    private static final int MAX_FUNCTION_CALLS = 10;
    private static final String ERROR_MESSAGE = "An error was catch in kernel stacktrace! Please check STEM logs for more information!";

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final MirraPlugin plugin;
    private final String name;
    private final String textOpenAIModel;
    private final String imageOpenAIModel;
    private final String aiRoleDescription;
    private final OpenAIClient openAiService;
    private final AIManager aiManager;
    /* one memory per user token; requests of the same user are serialized on it */
    private final Map<String, MemorySerializer> memorySerializerHashMap;

    public AIModel(MirraPlugin plugin, String name, String openAIToken, AIManager aiManager) {
        this.plugin = plugin;
        this.name = name;
        this.textOpenAIModel = plugin.getDefaultConfig().getString("model." + name + ".textOpenAIModel", "test123");
        this.imageOpenAIModel = plugin.getDefaultConfig().getString("model." + name + ".imageOpenAIModel", "test123");
        this.aiRoleDescription = plugin.getDefaultConfig().getString("model." + name + ".aiRoleDescription", "You are an assistant");
        plugin.getDefaultConfig().save();
        this.memorySerializerHashMap = new ConcurrentHashMap<>();
        this.aiManager = aiManager;
        this.openAiService = new OpenAIClientBuilder()
                .credential(new KeyCredential(openAIToken))
                .buildClient();
    }

    public ChatMessage getAiRoleDescription() {
        return new ChatMessage(this.aiRoleDescription, ChatRole.SYSTEM);
    }

    public String getName() {
        return name;
    }

    public String getTextOpenAIModel() {
        return textOpenAIModel;
    }

    public String getImageOpenAIModel() {
        return imageOpenAIModel;
    }

    private MemorySerializer getMemory(UserToken userToken) {
        return this.memorySerializerHashMap.computeIfAbsent(userToken.getName(), key -> new MemorySerializer(this.plugin, this, userToken));
    }

    private ChatMessage requestCompletion(LinkedList<ChatMessage> dataToSend) {
        dataToSend.addFirst(this.getAiRoleDescription());
        ChatCompletionsOptions options = new ChatCompletionsOptions(ChatMessage.convertToRequestMessage(dataToSend));
        options.setN(1);
        options.setUser("STEM-SYSTEM");
        options.setModel(this.textOpenAIModel);
        options.setFunctionCall(FunctionCallConfig.AUTO);
        options.setFunctions(this.aiManager.getFunctionProvider().buildChatFunctionProvider());
        ChatCompletions chatCompletions = this.openAiService.getChatCompletions(this.textOpenAIModel, options);
        if (chatCompletions == null || chatCompletions.getChoices() == null || chatCompletions.getChoices().isEmpty()) {
            throw new IllegalStateException("OpenAI returned no choices");
        }
        ChatMessage result = ChatMessage.buildsFrom(chatCompletions.getChoices().get(0).getMessage());
        if (result == null) {
            throw new IllegalStateException("OpenAI returned no assistant message");
        }
        return result;
    }

    /**
     * Executes the function calls requested by the model and sends the results back until the model
     * answers with text.
     */
    private ChatMessage resolveFunctionCalls(ChatMessage result, MemorySerializer memory, IdentityUser identityUser, UserToken userToken) {
        int calls = 0;
        while (result.hasFunctionCall()) {
            if (++calls > MAX_FUNCTION_CALLS) {
                this.plugin.getPluginLogger().warning("Too many function calls in one request for " + userToken.getName() + ", aborted");
                return new ChatMessage("Too many function calls, request aborted.", ChatRole.ASSISTANT);
            }
            String functionName = result.getFunctionCall().getName();
            this.plugin.getPluginLogger().debug("Function call received: " + functionName);
            JSONObject response;
            if (this.aiManager.getFunctionProvider().hasFunction(functionName)) {
                IFunctionCall function = this.aiManager.getFunctionProvider().getFunction(functionName);
                try {
                    String arguments = result.getFunctionCall().getArguments();
                    JSONObject inputArguments = new JSONObject(arguments == null || arguments.isBlank() ? "{}" : arguments);
                    response = function.completeRequest(inputArguments, identityUser, userToken);
                } catch (JSONException e) {
                    response = new JSONObject().put("success", false).put("reason", "Invalid arguments: " + e.getMessage());
                } catch (Exception e) {
                    this.plugin.getPluginLogger().error("Function " + functionName + " failed", e);
                    response = new JSONObject().put("success", false).put("reason", "Internal error: " + e.getMessage());
                }
                if (response == null) {
                    response = new JSONObject().put("success", false).put("reason", "No result");
                }
            } else {
                response = this.aiManager.getFunctionProvider().functionNotFound();
            }
            memory.memorizeData(new ChatMessage(new ChatRequestFunctionMessage(functionName, response.toString())));
            /* Resend the data again to the model with the function data */
            result = this.requestCompletion(memory.accessMemory());
            this.plugin.getPluginLogger().debug("Function call finished: " + functionName);
            memory.memorizeData(result);
        }
        return result;
    }

    public String requestChatCompletion(List<String> inputData, UserToken userToken, String displayName) {
        IdentityUser identityUser = this.plugin.getIdentityManager().getIdentityUserByToken(userToken);
        if (identityUser instanceof IdentityGuest identityGuest) {
            identityGuest.setGuestName(displayName);
        }
        this.plugin.getPluginLogger().debug("Request by identityUser " + identityUser.getIdentityName());
        MemorySerializer memory = this.getMemory(userToken);
        ChatMessage result;
        synchronized (memory) {
            try {
                for (String input : inputData) {
                    memory.memorizeData(new ChatMessage(input, ChatRole.USER));
                }
                result = this.requestCompletion(memory.accessMemory());
                memory.memorizeData(result);
                result = this.resolveFunctionCalls(result, memory, identityUser, userToken);
            } catch (Exception e) {
                this.plugin.getPluginLogger().error("Chat completion failed", e);
                result = new ChatMessage(ERROR_MESSAGE + " \n" + generateStacktrace(e), ChatRole.ASSISTANT);
            }
        }
        return result.getContent();
    }

    public String requestImageCompletion(String prompt) {
        ImageGenerationOptions options = new ImageGenerationOptions(prompt);
        options.setN(1);
        options.setModel(this.imageOpenAIModel);
        options.setUser("STEM-SYSTEM");
        Path tempFile = null;
        try {
            ImageGenerations imageGenerations = this.openAiService.getImageGenerations(this.imageOpenAIModel, options);
            List<ImageGenerationData> results = imageGenerations.getData();
            if (results == null || results.isEmpty() || results.get(0).getUrl() == null) {
                throw new IllegalStateException("OpenAI returned no image");
            }
            String imageUrl = results.get(0).getUrl();
            if (!this.plugin.getStemApi().cloud().isEnabled()) {
                return imageUrl;
            }
            Path tempDirectory = this.plugin.getTempFolder().toPath().resolve("cloudUpload");
            Files.createDirectories(tempDirectory);
            tempFile = Files.createTempFile(tempDirectory, "openai_", ".png");
            HttpRequest request = HttpRequest.newBuilder(URI.create(imageUrl)).timeout(Duration.ofSeconds(60)).GET().build();
            HttpResponse<Path> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofFile(tempFile));
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Image download failed with HTTP " + response.statusCode());
            }
            Optional<CloudFileHandle> cloudFile = this.plugin.getStemApi().cloud().uploadWithRandomName(tempFile.toFile(), "/GeneratedImages/");
            return cloudFile.map(CloudFileHandle::createPublicShareLink)
                    .orElseThrow(() -> new IllegalStateException("Error while uploading file to cloud!"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ERROR_MESSAGE;
        } catch (Exception e) {
            this.plugin.getPluginLogger().error("Image completion failed", e);
            return ERROR_MESSAGE + "\n" + generateStacktrace(e);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception ignored) {
                    /* temp folder is cleaned by STEM */
                }
            }
        }
    }

    public String requestStandaloneFunctionCall(String functionName, JSONObject jsonObject, UserToken userToken) {
        MemorySerializer memory = this.getMemory(userToken);
        IdentityUser identityUser = this.plugin.getIdentityManager().getIdentityUserByToken(userToken);
        ChatMessage result;
        synchronized (memory) {
            try {
                if (this.aiManager.getFunctionProvider().hasFunction(functionName)) {
                    this.plugin.getPluginLogger().debug("Standalone function call started: " + functionName);
                    IFunctionCall function = this.aiManager.getFunctionProvider().getFunction(functionName);
                    ChatMessage functionResponse = new ChatMessage(new ChatRequestFunctionMessage(function.functionName(), jsonObject.toString()));
                    memory.memorizeData(functionResponse);
                    LinkedList<ChatMessage> dataToSend = new LinkedList<>();
                    dataToSend.add(functionResponse);
                    result = this.requestCompletion(dataToSend);
                    if (result.hasFunctionCall()) {
                        memory.memorizeData(result);
                        result = this.resolveFunctionCalls(result, memory, identityUser, userToken);
                    } else {
                        memory.memorizeData(result);
                    }
                    this.plugin.getPluginLogger().debug("Standalone function call finished: " + functionName);
                } else {
                    result = new ChatMessage("No function found with this name!", ChatRole.ASSISTANT);
                }
            } catch (Exception e) {
                this.plugin.getPluginLogger().error("Standalone function call failed", e);
                result = new ChatMessage(ERROR_MESSAGE + " \n" + generateStacktrace(e), ChatRole.ASSISTANT);
            }
        }
        return result.getContent();
    }

    public List<String> buildMessageBlock(String sender, String content, String source) {
        List<String> input = new ArrayList<>();
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("sender", sender);
        jsonObject.put("content", content);
        jsonObject.put("source", source);
        jsonObject.put("timestamp", new SimpleDateFormat("dd.MM.yyyy - HH:mm:ss").format(new Date()));
        input.add(jsonObject.toString());
        return input;
    }

    public String generateStacktrace(Exception e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e).append("\n");
        StackTraceElement[] stackTrace = e.getStackTrace();
        /* only the top of the trace, the full trace is in the STEM log */
        for (int i = 0; i < Math.min(10, stackTrace.length); i++) {
            sb.append(stackTrace[i].toString()).append("\n");
        }
        return sb.toString();
    }
}
