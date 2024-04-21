package de.linzn.mirra.core;

import com.theokanning.openai.completion.chat.*;
import com.theokanning.openai.image.CreateImageRequest;
import com.theokanning.openai.image.Image;
import com.theokanning.openai.service.OpenAiService;
import de.linzn.mirra.MirraPlugin;
import de.linzn.mirra.core.functions.IFunction;
import de.stem.stemSystem.STEMSystemApp;
import org.json.JSONObject;

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

    public String requestChatCompletion(List<String> inputData, String identity) {
        if (!this.memorySerializerHashMap.containsKey(identity)) {
            this.memorySerializerHashMap.put(identity, new MemorySerializer(this, identity));
        }

        for (String input : inputData) {
            ChatMessage chatMessage = new ChatMessage();
            chatMessage.setRole("user");
            chatMessage.setContent(input);
            this.memorySerializerHashMap.get(identity).memorizeData(chatMessage);
        }

        LinkedList<ChatMessage> dataToSend = this.memorySerializerHashMap.get(identity).accessMemory();
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
            this.memorySerializerHashMap.get(identity).memorizeData(result);

            ChatFunctionCall functionCall = result.getFunctionCall();
            while (functionCall != null) {
                STEMSystemApp.LOGGER.CORE("Function call received");
                if (MirraPlugin.mirraPlugin.getAiManager().getFunctionProvider().hasFunction(functionCall.getName())) {
                    IFunction function = MirraPlugin.mirraPlugin.getAiManager().getFunctionProvider().getFunction(functionCall.getName());
                    JSONObject jsonObject = function.completeRequest(new JSONObject(functionCall.getArguments().toString()));
                    ChatMessage functionResponse = new ChatMessage(ChatMessageRole.FUNCTION.value(), jsonObject.toString(), functionCall.getName());
                    this.memorySerializerHashMap.get(identity).memorizeData(functionResponse);
                }
                /* Resend the data again to the model with the function data */
                dataToSend = this.memorySerializerHashMap.get(identity).accessMemory();
                dataToSend.addFirst(this.getAiRoleDescription());
                completionRequest.setMessages(dataToSend);

                results = this.openAiService.createChatCompletion(completionRequest).getChoices();
                result = results.get(0).getMessage();
                functionCall = result.getFunctionCall();
                STEMSystemApp.LOGGER.CORE("Function call finished");
                this.memorySerializerHashMap.get(identity).memorizeData(result);
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
            url = new StringBuilder(results.get(0).getUrl());
        } catch (Exception e) {
            STEMSystemApp.LOGGER.ERROR(e);
            url = new StringBuilder("An error was catch in kernel stacktrace! Please check STEM logs for more information!");
        }

        return url.toString();
    }

    public String requestStandaloneFunctionCall(String functionName, JSONObject jsonObject, String identity) {
        if (!this.memorySerializerHashMap.containsKey(identity)) {
            this.memorySerializerHashMap.put(identity, new MemorySerializer(this, identity));
        }
        ChatMessage result;
        LinkedList<ChatMessage> dataToSend = new LinkedList<>();
        try {
            if (MirraPlugin.mirraPlugin.getAiManager().getFunctionProvider().hasFunction(functionName)) {
                IFunction function = MirraPlugin.mirraPlugin.getAiManager().getFunctionProvider().getFunction(functionName);
                ChatMessage functionResponse = new ChatMessage(ChatMessageRole.FUNCTION.value(), jsonObject.toString(), function.functionName());
                dataToSend.add(functionResponse);

                this.memorySerializerHashMap.get(identity).memorizeData(functionResponse);

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
                this.memorySerializerHashMap.get(identity).memorizeData(result);
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
