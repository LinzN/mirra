package de.linzn.mirra.core.functions;

import com.theokanning.openai.completion.chat.ChatFunctionDynamic;
import com.theokanning.openai.completion.chat.ChatFunctionProperty;
import de.linzn.mirra.MirraPlugin;
import de.linzn.mirra.identitySystem.AiPermissions;
import de.linzn.mirra.identitySystem.IdentityUser;
import de.stem.stemSystem.STEMSystemApp;
import org.json.JSONObject;

public class CreateImage implements IFunction {
    @Override
    public JSONObject completeRequest(JSONObject input, IdentityUser identityUser) {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("imageDescription", input.getString("imageDescription"));
        if(identityUser.hasPermission(AiPermissions.CREATE_IMAGE)){
            STEMSystemApp.LOGGER.CORE(input);
            String url = MirraPlugin.mirraPlugin.getAiManager().getDefaultModel().requestImageCompletion(input.getString("imageDescription"));
            jsonObject.put("success", true);
            jsonObject.put("imageURL", url);
        } else {
            jsonObject.put("success", false);
            jsonObject.put("reason", "No permissions");
        }
        return jsonObject;
    }

    @Override
    public ChatFunctionDynamic getFunctionString() {
        return ChatFunctionDynamic.builder()
                .name(this.functionName())
                .description("Create or draw an image based of a given description and returns as a web url")
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
