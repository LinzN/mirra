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

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class CreateReminder implements IFunctionCall {
    @Override
    public JSONObject completeRequest(JSONObject input, IdentityUser identityUser, UserToken userToken) {
        STEMSystemApp.LOGGER.CORE(input);
        JSONObject jsonObject = new JSONObject();
        if (identityUser.hasPermission(AiPermissions.CREATE_REMINDER)) {
            jsonObject.put("success", true);
            String inputContent = input.getString("reminderContent");
            Date date;
            try {
                date = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss").parse(input.getString("reminderDatetime"));
                MirraPlugin.mirraPlugin.getReminderEngine().createMirraReminder(identityUser, userToken, inputContent, date);

            } catch (ParseException e) {
                jsonObject.put("success", false);
                jsonObject.put("reason", "Error: " + e.getLocalizedMessage());
            }

            STEMSystemApp.LOGGER.CORE(input);
        } else {
            jsonObject.put("success", false);
            jsonObject.put("reason", "No permissions");
        }
        return jsonObject;
    }

    @Override
    public FunctionDefinition getFunctionString() {
        return new FunctionDefinition(this.functionName())
                .setDescription("Create a reminder for something")
                .setParameters(new FunctionParameters()
                        .setType("object")
                        .addProperty(new FunctionProperties()
                                .setName("reminderContent")
                                .setType("string")
                                .setDescription("The content of the reminder")
                                .setRequired(true))
                        .addProperty(new FunctionProperties()
                                .setName("reminderDatetime")
                                .setType("string")
                                .setDescription("The time for the reminder as datetime. Format example: 13-06-2024 16:45:00 (dd-MM-yyyy HH:mm:ss)")
                                .setRequired(true))
                        .build());
    }

    @Override
    public String functionName() {
        return "create_reminder";
    }

}
