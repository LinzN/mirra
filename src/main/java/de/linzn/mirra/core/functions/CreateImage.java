package de.linzn.mirra.core.functions;

import com.azure.ai.openai.models.FunctionDefinition;
import de.linzn.mirra.MirraPlugin;
import de.linzn.mirra.identitySystem.AiPermissions;
import de.linzn.mirra.identitySystem.IdentityUser;
import de.linzn.mirra.identitySystem.UserToken;
import de.linzn.mirra.openai.IFunctionCall;
import de.linzn.mirra.openai.models.FunctionParameters;
import de.linzn.mirra.openai.models.FunctionProperties;
import de.stem.stemSystem.STEMSystemApp;
import org.json.JSONObject;

public class CreateImage implements IFunctionCall {
    @Override
    public JSONObject completeRequest(JSONObject input, IdentityUser identityUser, UserToken userToken) {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("imageDescription", input.getString("imageDescription"));
        if (identityUser.hasPermission(AiPermissions.CREATE_IMAGE)) {
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
    public FunctionDefinition getFunctionString() {
        return new FunctionDefinition(this.functionName())
                .setDescription("Create or draw an image based of a given description and returns as a web url")
                .setParameters(new FunctionParameters()
                        .setType("object")
                        .addProperty(new FunctionProperties()
                                .setName("imageDescription")
                                .setType("string")
                                .setDescription("The description to create the image like 'A black cat sitting in front of a house while sunrises'")
                                .setRequired(true))
                        .build());
    }

    @Override
    public String functionName() {
        return "create_image";
    }

}
