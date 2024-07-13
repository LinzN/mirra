package de.linzn.mirra.core;

import com.theokanning.openai.completion.chat.*;
import com.theokanning.openai.image.CreateImageRequest;
import com.theokanning.openai.image.Image;
import com.theokanning.openai.service.OpenAiService;
import de.linzn.mirra.MirraPlugin;
import de.linzn.mirra.core.functions.IFunction;
import de.linzn.mirra.identitySystem.IdentityGuest;
import de.linzn.mirra.identitySystem.IdentityUser;
import de.linzn.mirra.identitySystem.UserToken;
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
import java.time.Duration;
import java.util.*;

public class AIModel {
    private final String name;
    private final String textOpenAIModel;
    private final String imageOpenAIModel;
    private final String aiRoleDescription;
    private final OpenAiService openAiService;
    private final HashMap<String, MemorySerializer> memorySerializerHashMap;


    public AIModel(String name, String openAIToken) {
        this.name = name;
        this.textOpenAIModel = MirraPlugin.mirraPlugin.getDefaultConfig().getString("model." + name + ".textOpenAIModel", "test123");
        this.imageOpenAIModel = MirraPlugin.mirraPlugin.getDefaultConfig().getString("model." + name + ".imageOpenAIModel", "test123");
        this.aiRoleDescription = MirraPlugin.mirraPlugin.getDefaultConfig().getString("model." + name + ".aiRoleDescription", "You are an assistant");
        MirraPlugin.mirraPlugin.getDefaultConfig().save();
        this.memorySerializerHashMap = new HashMap<>();
        this.openAiService = new OpenAiService(openAIToken, Duration.ofMinutes(2));
    }


    public ChatMessage getAiRoleDescription() {
        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setRole("system");
        chatMessage.setContent(this.aiRoleDescription);
        return chatMessage;
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
            ChatMessage chatMessage = new ChatMessage();
            chatMessage.setRole("user");
            chatMessage.setContent(input);
            this.memorySerializerHashMap.get(userToken.getName()).memorizeData(chatMessage);
        }

        LinkedList<ChatMessage> dataToSend = this.memorySerializerHashMap.get(userToken.getName()).accessMemory();
        dataToSend.addFirst(this.getAiRoleDescription());
        ChatCompletionRequest completionRequest = ChatCompletionRequest.builder()
                .messages(dataToSend)
                .model(this.textOpenAIModel)
                .functions(MirraPlugin.mirraPlugin.getAiManager().getFunctionProvider().buildChatFunctionProvider())
                .functionCall(ChatCompletionRequest.ChatCompletionRequestFunctionCall.of("auto"))
                .n(1)
                .user("STEM-SYSTEM")
                .build();

        ChatMessage result;
        try {
            List<ChatCompletionChoice> results = this.openAiService.createChatCompletion(completionRequest).getChoices();
            result = results.get(0).getMessage();
            this.memorySerializerHashMap.get(userToken.getName()).memorizeData(result);

            ChatFunctionCall functionCall = result.getFunctionCall();
            while (functionCall != null) {
                STEMSystemApp.LOGGER.CORE("Function call received");
                if (MirraPlugin.mirraPlugin.getAiManager().getFunctionProvider().hasFunction(functionCall.getName())) {
                    IFunction function = MirraPlugin.mirraPlugin.getAiManager().getFunctionProvider().getFunction(functionCall.getName());
                    JSONObject inputArguments = new JSONObject(functionCall.getArguments().toString());
                    JSONObject jsonObject = function.completeRequest(inputArguments, identityUser, userToken);
                    ChatMessage functionResponse = new ChatMessage(ChatMessageRole.FUNCTION.value(), jsonObject.toString(), functionCall.getName());
                    this.memorySerializerHashMap.get(userToken.getName()).memorizeData(functionResponse);
                }
                /* Resend the data again to the model with the function data */
                dataToSend = this.memorySerializerHashMap.get(userToken.getName()).accessMemory();
                dataToSend.addFirst(this.getAiRoleDescription());
                completionRequest.setMessages(dataToSend);

                results = this.openAiService.createChatCompletion(completionRequest).getChoices();
                result = results.get(0).getMessage();
                functionCall = result.getFunctionCall();
                STEMSystemApp.LOGGER.CORE("Function call finished");
                this.memorySerializerHashMap.get(userToken.getName()).memorizeData(result);
            }
        } catch (Exception e) {
            STEMSystemApp.LOGGER.ERROR(e);
            ChatMessage chatMessage = new ChatMessage();
            chatMessage.setContent("An error was catch in kernel stacktrace! Please check STEM logs for more information! \n" + e.getMessage());
            result = chatMessage;
        }

        return result.getContent();
    }

    public String requestImageCompletion(String prompt) {
        CreateImageRequest imageRequest = CreateImageRequest.builder()
                .prompt(prompt)
                .model(this.imageOpenAIModel)
                .n(1)
                .user("STEM-SYSTEM")
                .build();
        StringBuilder url;
        try {
            List<Image> results = this.openAiService.createImage(imageRequest).getData();
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
        ChatMessage result;
        LinkedList<ChatMessage> dataToSend = new LinkedList<>();
        try {
            if (MirraPlugin.mirraPlugin.getAiManager().getFunctionProvider().hasFunction(functionName)) {
                IFunction function = MirraPlugin.mirraPlugin.getAiManager().getFunctionProvider().getFunction(functionName);
                ChatMessage functionResponse = new ChatMessage(ChatMessageRole.FUNCTION.value(), jsonObject.toString(), function.functionName());
                dataToSend.add(functionResponse);

                this.memorySerializerHashMap.get(userToken.getName()).memorizeData(functionResponse);

                dataToSend.addFirst(this.getAiRoleDescription());
                ChatCompletionRequest completionRequest = ChatCompletionRequest.builder()
                        .messages(dataToSend)
                        .model(this.textOpenAIModel)
                        .functions(MirraPlugin.mirraPlugin.getAiManager().getFunctionProvider().buildChatFunctionProvider())
                        .functionCall(ChatCompletionRequest.ChatCompletionRequestFunctionCall.of("auto"))
                        .n(1)
                        .user("STEM-SYSTEM")
                        .build();

                List<ChatCompletionChoice> results = this.openAiService.createChatCompletion(completionRequest).getChoices();
                result = results.get(0).getMessage();
                this.memorySerializerHashMap.get(userToken.getName()).memorizeData(result);
            } else {
                ChatMessage chatMessage = new ChatMessage();
                chatMessage.setContent("No function found with this name!");
                result = chatMessage;
            }
        } catch (Exception e) {
            STEMSystemApp.LOGGER.ERROR(e);
            ChatMessage chatMessage = new ChatMessage();
            chatMessage.setContent("An error was catch in kernel stacktrace! Please check STEM logs for more information! \n" + e.getMessage());
            result = chatMessage;
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
