package de.linzn.mirra.core.functions;

import com.theokanning.openai.completion.chat.ChatFunctionDynamic;
import com.theokanning.openai.completion.chat.ChatFunctionProperty;
import de.linzn.mirra.identitySystem.AiPermissions;
import de.linzn.mirra.identitySystem.IdentityUser;
import de.linzn.mirra.identitySystem.UserToken;
import de.stem.stemSystem.STEMSystemApp;
import org.json.JSONObject;

import java.util.Date;
import java.util.concurrent.TimeUnit;

public class FuelPrice implements IFunction {
    @Override
    public JSONObject completeRequest(JSONObject input, IdentityUser identityUser, UserToken userToken) {
        JSONObject jsonObject = new JSONObject();
        if (identityUser.hasPermission(AiPermissions.GET_FUEL_PRICE)) {
            jsonObject.put("success", true);
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
                .description("Get the price list of the cheapest fuel station near given location")
                .addProperty(ChatFunctionProperty.builder()
                        .name("location")
                        .type("string")
                        .description("The location to get the cheapest fuel station around! Like 'Blieskastel'")
                        .required(true)
                        .build())
                .build();
    }

    @Override
    public String functionName() {
        return "get_fuel_price";
    }

}
