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
import de.stem.stemSystem.STEMSystemApp;
import de.stem.stemSystem.modules.cloudModule.CloudFile;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.*;

public class AIModel {
    private final String name;
    private final String textOpenAIModel;
    private final String imageOpenAIModel;
    private final String aiRoleDescription;
    private final OpenAIClient openAiService;
    private final AIManager aiManager;
    private final HashMap<String, MemorySerializer> memorySerializerHashMap;


    public AIModel(String name, String openAIToken, AIManager aiManager) {
        this.name = name;
        this.textOpenAIModel = MirraPlugin.mirraPlugin.getDefaultConfig().getString("model." + name + ".textOpenAIModel", "test123");
        this.imageOpenAIModel = MirraPlugin.mirraPlugin.getDefaultConfig().getString("model." + name + ".imageOpenAIModel", "test123");
        this.aiRoleDescription = MirraPlugin.mirraPlugin.getDefaultConfig().getString("model." + name + ".aiRoleDescription", "You are an assistant");
        MirraPlugin.mirraPlugin.getDefaultConfig().save();
        this.memorySerializerHashMap = new HashMap<>();
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

    public String requestChatCompletion(List<String> inputData, UserToken userToken, String displayName) {
        IdentityUser identityUser = MirraPlugin.mirraPlugin.getIdentityManager().getIdentityUserByToken(userToken);
        if (identityUser instanceof IdentityGuest) {
            ((IdentityGuest) identityUser).setGuestName(displayName);
        }
        STEMSystemApp.LOGGER.CONFIG("Request by identityUser " + identityUser.getIdentityName());
        if (!this.memorySerializerHashMap.containsKey(userToken.getName())) {
            this.memorySerializerHashMap.put(userToken.getName(), new MemorySerializer(this, userToken));
        }

        for (String input : inputData) {
            ChatMessage chatMessage = new ChatMessage(input, ChatRole.USER);
            this.memorySerializerHashMap.get(userToken.getName()).memorizeData(chatMessage);
        }

        LinkedList<ChatMessage> dataToSend = this.memorySerializerHashMap.get(userToken.getName()).accessMemory();
        dataToSend.addFirst(this.getAiRoleDescription());

        ChatCompletionsOptions options = new ChatCompletionsOptions(ChatMessage.convertToRequestMessage(dataToSend));
        options.setN(1);
        options.setUser("STEM-SYSTEM");
        options.setModel(this.textOpenAIModel);
        options.setFunctionCall(FunctionCallConfig.AUTO);
        options.setFunctions(this.aiManager.getFunctionProvider().buildChatFunctionProvider());

        ChatMessage result;
        try {
            ChatCompletions chatCompletions = this.openAiService.getChatCompletions(this.textOpenAIModel, options);
            ChatChoice choice = chatCompletions.getChoices().get(0);
            result = ChatMessage.buildsFrom(choice.getMessage());

            this.memorySerializerHashMap.get(userToken.getName()).memorizeData(result);

            while (result.hasFunctionCall()) {
                STEMSystemApp.LOGGER.CORE("Function call received");

                if (this.aiManager.getFunctionProvider().hasFunction(result.getFunctionCall().getName())) {
                    IFunctionCall function = this.aiManager.getFunctionProvider().getFunction(result.getFunctionCall().getName());
                    JSONObject inputArguments = new JSONObject(result.getFunctionCall().getArguments());
                    JSONObject jsonObject = function.completeRequest(inputArguments, identityUser, userToken);
                    ChatMessage functionResponse = new ChatMessage(new ChatRequestFunctionMessage(result.getFunctionCall().getName(), jsonObject.toString()));
                    this.memorySerializerHashMap.get(userToken.getName()).memorizeData(functionResponse);
                } else {
                    ChatMessage functionNotFoundResponse = new ChatMessage(new ChatRequestFunctionMessage(result.getFunctionCall().getName(), this.aiManager.getFunctionProvider().functionNotFound().toString()));
                    this.memorySerializerHashMap.get(userToken.getName()).memorizeData(functionNotFoundResponse);
                }

                /* Resend the data again to the model with the function data */
                dataToSend = this.memorySerializerHashMap.get(userToken.getName()).accessMemory();
                dataToSend.addFirst(this.getAiRoleDescription());

                options = new ChatCompletionsOptions(ChatMessage.convertToRequestMessage(dataToSend));
                options.setN(1);
                options.setUser("STEM-SYSTEM");
                options.setModel(this.textOpenAIModel);
                options.setFunctionCall(FunctionCallConfig.AUTO);
                options.setFunctions(this.aiManager.getFunctionProvider().buildChatFunctionProvider());

                chatCompletions = this.openAiService.getChatCompletions(this.textOpenAIModel, options);
                choice = chatCompletions.getChoices().get(0);
                result = ChatMessage.buildsFrom(choice.getMessage());

                STEMSystemApp.LOGGER.CORE("Function call finished");
                this.memorySerializerHashMap.get(userToken.getName()).memorizeData(result);

            }
        } catch (Exception e) {
            STEMSystemApp.LOGGER.ERROR(e);
            result = new ChatMessage("An error was catch in kernel stacktrace! Please check STEM logs for more information! \n" + e.getMessage(), ChatRole.ASSISTANT);
        }

        return result.getContent();
    }

    public String requestImageCompletion(String prompt) {
        ImageGenerationOptions options = new ImageGenerationOptions(prompt);
        options.setN(1);
        options.setModel(this.imageOpenAIModel);
        options.setUser("STEM-SYSTEM");

        StringBuilder url;
        try {
            ImageGenerations imageGenerations = this.openAiService.getImageGenerations(this.imageOpenAIModel, options);

            List<ImageGenerationData> results = imageGenerations.getData();
            URL openaiImageUrl = new URL(results.get(0).getUrl());

            File tempDirectory = new File(MirraPlugin.mirraPlugin.getDataFolder(), "temp");
            if (!tempDirectory.exists()) {
                tempDirectory.mkdir();
            }

            File tempFile = new File(tempDirectory, "picture.png");
            InputStream in = openaiImageUrl.openStream();
            Files.copy(in, Paths.get(tempFile.getPath()), StandardCopyOption.REPLACE_EXISTING);

            CloudFile cloudFile = STEMSystemApp.getInstance().getCloudModule().uploadFileRandomName(tempFile, "/GeneratedImages/");
            if (cloudFile != null) {
                String nextcloudURL = cloudFile.createPublicShareLink();
                url = new StringBuilder(nextcloudURL);
            } else {
                throw new IllegalArgumentException("Error while uploading file to cloud!");
            }

        } catch (Exception e) {
            STEMSystemApp.LOGGER.ERROR(e);
            url = new StringBuilder("An error was catch in kernel stacktrace! Please check STEM logs for more information!");
        }

        return url.toString();
    }

    public String requestStandaloneFunctionCall(String functionName, JSONObject jsonObject, UserToken userToken) {
        if (!this.memorySerializerHashMap.containsKey(userToken.getName())) {
            this.memorySerializerHashMap.put(userToken.getName(), new MemorySerializer(this, userToken));
        }

        IdentityUser identityUser = MirraPlugin.mirraPlugin.getIdentityManager().getIdentityUserByToken(userToken);

        ChatMessage result;
        LinkedList<ChatMessage> dataToSend = new LinkedList<>();
        try {
            if (this.aiManager.getFunctionProvider().hasFunction(functionName)) {
                STEMSystemApp.LOGGER.CORE("Standalone function call started");
                IFunctionCall function = this.aiManager.getFunctionProvider().getFunction(functionName);
                ChatRequestFunctionMessage chatRequestFunctionMessage = new ChatRequestFunctionMessage(function.functionName(), jsonObject.toString());
                ChatMessage functionResponse = new ChatMessage(chatRequestFunctionMessage);
                dataToSend.add(functionResponse);

                this.memorySerializerHashMap.get(userToken.getName()).memorizeData(functionResponse);

                dataToSend.addFirst(this.getAiRoleDescription());
                ChatCompletionsOptions options = new ChatCompletionsOptions(ChatMessage.convertToRequestMessage(dataToSend));
                options.setN(1);
                options.setUser("STEM-SYSTEM");
                options.setModel(this.textOpenAIModel);
                options.setFunctionCall(FunctionCallConfig.AUTO);
                options.setFunctions(this.aiManager.getFunctionProvider().buildChatFunctionProvider());


                ChatCompletions chatCompletions = this.openAiService.getChatCompletions(this.textOpenAIModel, options);
                ChatChoice choice = chatCompletions.getChoices().get(0);
                result = ChatMessage.buildsFrom(choice.getMessage());

                while (result.hasFunctionCall()) {
                    STEMSystemApp.LOGGER.CORE("Manual function call received");
                    if (this.aiManager.getFunctionProvider().hasFunction(result.getFunctionCall().getName())) {
                        IFunctionCall requestFunction = this.aiManager.getFunctionProvider().getFunction(result.getFunctionCall().getName());
                        JSONObject requestInputArguments = new JSONObject(result.getFunctionCall().getArguments());
                        JSONObject requestJsonObject = requestFunction.completeRequest(requestInputArguments, identityUser, userToken);// bugfix nullpointer???
                        ChatMessage requestFunctionResponse = new ChatMessage(new ChatRequestFunctionMessage(result.getFunctionCall().getName(), requestJsonObject.toString()));
                        this.memorySerializerHashMap.get(userToken.getName()).memorizeData(requestFunctionResponse);
                    } else {
                        ChatMessage functionNotFoundResponse = new ChatMessage(new ChatRequestFunctionMessage(result.getFunctionCall().getName(), this.aiManager.getFunctionProvider().functionNotFound().toString()));
                        this.memorySerializerHashMap.get(userToken.getName()).memorizeData(functionNotFoundResponse);
                    }

                    /* Resend the data again to the model with the function data */
                    dataToSend = this.memorySerializerHashMap.get(userToken.getName()).accessMemory();
                    dataToSend.addFirst(this.getAiRoleDescription());

                    options = new ChatCompletionsOptions(ChatMessage.convertToRequestMessage(dataToSend));
                    options.setN(1);
                    options.setUser("STEM-SYSTEM");
                    options.setModel(this.textOpenAIModel);
                    options.setFunctionCall(FunctionCallConfig.AUTO);
                    options.setFunctions(this.aiManager.getFunctionProvider().buildChatFunctionProvider());

                    chatCompletions = this.openAiService.getChatCompletions(this.textOpenAIModel, options);
                    choice = chatCompletions.getChoices().get(0);
                    result = ChatMessage.buildsFrom(choice.getMessage());

                    STEMSystemApp.LOGGER.CORE("Manual function call finished");
                }


                this.memorySerializerHashMap.get(userToken.getName()).memorizeData(result);
                STEMSystemApp.LOGGER.CORE("Standalone function call finished");
            } else {
                result = new ChatMessage("No function found with this name!", ChatRole.ASSISTANT);
            }
        } catch (Exception e) {
            STEMSystemApp.LOGGER.ERROR(e);
            result = new ChatMessage("An error was catch in kernel stacktrace! Please check STEM logs for more information! \n" + e.getMessage(), ChatRole.ASSISTANT);
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

}
