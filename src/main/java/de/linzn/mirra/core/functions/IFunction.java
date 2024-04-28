package de.linzn.mirra.core.functions;

import com.theokanning.openai.completion.chat.ChatFunctionDynamic;
import de.linzn.mirra.identitySystem.IdentityUser;
import org.json.JSONObject;

public interface IFunction {

    JSONObject completeRequest(JSONObject input, IdentityUser identityUser);

    ChatFunctionDynamic getFunctionString();

    String functionName();
}
