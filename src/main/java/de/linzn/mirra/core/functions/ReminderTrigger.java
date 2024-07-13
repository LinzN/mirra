package de.linzn.mirra.core.functions;

import com.theokanning.openai.completion.chat.ChatFunctionDynamic;
import de.linzn.mirra.identitySystem.IdentityUser;
import de.linzn.mirra.identitySystem.UserToken;
import de.stem.stemSystem.STEMSystemApp;
import org.json.JSONObject;

public class ReminderTrigger implements IFunction {
    @Override
    public JSONObject completeRequest(JSONObject input, IdentityUser identityUser, UserToken userToken) {
        STEMSystemApp.LOGGER.CORE("Tis is a standalone function. No external call allowed");
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("success", false);
        jsonObject.put("description", "This function call is not allowed to trigger external!");
        return jsonObject;
    }

    @Override
    public ChatFunctionDynamic getFunctionString() {
        return ChatFunctionDynamic.builder()
                .name(this.functionName())
                .description("Trigger a reminder that was set before by the reminderTargetUsername")
                .build();
    }

    @Override
    public String functionName() {
        return "trigger_reminder";
    }

}
