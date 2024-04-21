package de.linzn.mirra.core.functions;

import com.theokanning.openai.completion.chat.ChatFunctionDynamic;
import org.json.JSONObject;

public interface IFunction {

    JSONObject completeRequest(JSONObject input);

    ChatFunctionDynamic getFunctionString();

    String functionName();
}
