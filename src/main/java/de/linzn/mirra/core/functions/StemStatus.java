package de.linzn.mirra.core.functions;

import com.theokanning.openai.completion.chat.ChatFunctionDynamic;
import de.linzn.mirra.identitySystem.AiPermissions;
import de.linzn.mirra.identitySystem.IdentityUser;
import de.linzn.mirra.identitySystem.UserToken;
import de.stem.stemSystem.STEMSystemApp;
import org.json.JSONObject;

import java.util.Date;
import java.util.concurrent.TimeUnit;

public class StemStatus implements IFunction {
    @Override
    public JSONObject completeRequest(JSONObject input, IdentityUser identityUser, UserToken userToken) {
        JSONObject jsonObject = new JSONObject();
        if (identityUser.hasPermission(AiPermissions.STATUS_STEM)) {
            Date date = STEMSystemApp.getInstance().getUptimeDate();
            long diff = TimeUnit.MILLISECONDS.toSeconds(new Date().getTime() - date.getTime());
            String uptime = String.format("%d days, %02d:%02d:%02d", (diff / (3600 * 24)), diff / 3600, (diff % 3600) / 60, (diff % 60));

            jsonObject.put("success", true);
            jsonObject.put("uptime", uptime);
            jsonObject.put("status", "healthy");
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
                .description("Get the system status of the STEM Smarthome Framework like uptime and other stats")
                .build();
    }

    @Override
    public String functionName() {
        return "get_stem_status";
    }

}
