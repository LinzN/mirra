package de.linzn.mirra.core.functions;

import com.azure.ai.openai.models.FunctionDefinition;
import de.linzn.mirra.identitySystem.IdentityUser;
import de.linzn.mirra.identitySystem.UserToken;
import de.linzn.mirra.openai.IFunctionCall;
import de.linzn.mirra.openai.models.FunctionParameters;
import de.stem.stemSystem.STEMSystemApp;
import org.json.JSONObject;

public class EventTrigger implements IFunctionCall {
    @Override
    public JSONObject completeRequest(JSONObject input, IdentityUser identityUser, UserToken userToken) {
        STEMSystemApp.LOGGER.CORE("Tis is a standalone function. No external call allowed");
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("success", false);
        jsonObject.put("description", "This function call is not allowed to trigger external!");
        return jsonObject;
    }

    @Override
    public FunctionDefinition getFunctionString() {
        return new FunctionDefinition(this.functionName())
                .setDescription("Inform Niklas about a smart home event with a description coming in json format. Format Example {\"event\":\"door ring enabled\"}' to \"Hey Niklas, someone knocked at the door.\"")
                .setParameters(new FunctionParameters()
                        .setType("object")
                        .build());
    }

    @Override
    public String functionName() {
        return "trigger_event";
    }

}
