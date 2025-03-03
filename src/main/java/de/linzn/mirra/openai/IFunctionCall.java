package de.linzn.mirra.openai;

import com.azure.ai.openai.models.FunctionDefinition;
import de.linzn.mirra.identitySystem.IdentityUser;
import de.linzn.mirra.identitySystem.UserToken;
import org.json.JSONObject;

public interface IFunctionCall {

    JSONObject completeRequest(JSONObject input, IdentityUser identityUser, UserToken userToken);

    FunctionDefinition getFunctionString();

    String functionName();
}
