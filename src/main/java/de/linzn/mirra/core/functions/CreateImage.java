package de.linzn.mirra.core.functions;

import com.theokanning.openai.completion.chat.ChatFunctionDynamic;
import com.theokanning.openai.completion.chat.ChatFunctionParameters;
import com.theokanning.openai.completion.chat.ChatFunctionProperty;
import de.linzn.mirra.MirraPlugin;
import de.stem.stemSystem.STEMSystemApp;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;

public class CreateImage implements IFunction{
    @Override
    public JSONObject completeRequest(JSONObject input) {
        STEMSystemApp.LOGGER.CORE(input);
        String url = MirraPlugin.mirraPlugin.getAiManager().getDefaultModel().requestImageCompletion(input.getString("imageDescription"));
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("actionStatus", "success");
        jsonObject.put("imageDescription", input.getString("imageDescription"));
        jsonObject.put("imageURL", url);
        return jsonObject;
    }

    @Override
    public ChatFunctionDynamic getFunctionString() {
        return  ChatFunctionDynamic.builder()
                .name(this.functionName())
                .description("Create an image based of a given description and returns as a web url")
                .addProperty(ChatFunctionProperty.builder()
                        .name("imageDescription")
                        .type("string")
                        .description("The description to create the image like 'A black cat sitting in front of a house while sunrises'")
                        .required(true)
                        .build())
                .build();
    }

    @Override
    public String functionName() {
        return "create_image";
    }

}
