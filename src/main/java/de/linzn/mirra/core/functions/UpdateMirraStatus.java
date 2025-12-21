package de.linzn.mirra.core.functions;

import com.azure.ai.openai.models.FunctionDefinition;
import de.linzn.mirra.MirraPlugin;
import de.linzn.mirra.identitySystem.IdentityUser;
import de.linzn.mirra.identitySystem.UserToken;
import de.linzn.mirra.openai.IFunctionCall;
import de.linzn.mirra.openai.models.FunctionParameters;
import de.linzn.mirra.openai.models.FunctionProperties;
import net.dv8tion.jda.api.entities.Activity;
import org.json.JSONObject;

public class UpdateMirraStatus implements IFunctionCall {
    @Override
    public JSONObject completeRequest(JSONObject input, IdentityUser identityUser, UserToken userToken) {
        String newStatus = input.getString("new_status");
        MirraPlugin.mirraPlugin.getDiscordManager().getJda().getPresence().setActivity(Activity.playing(newStatus));
        //MirraPlugin.mirraPlugin.getWhatsappManager().getWhatsapp().changeAbout(newStatus);
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("success", true);
        jsonObject.put("new_status", newStatus);

        return jsonObject;
    }

    @Override
    public FunctionDefinition getFunctionString() {
        return new FunctionDefinition(this.functionName())
                .setDescription("Set your (Mirra) social status in Discord and Whatsapp")
                .setParameters(new FunctionParameters()
                        .setType("object")
                        .addProperty(new FunctionProperties()
                                .setName("new_status")
                                .setType("string")
                                .setDescription("The new Status message for Discord/Whatsapp")
                                .setRequired(true))
                        .build());
    }

    @Override
    public String functionName() {
        return "set_mirra_status";
    }

}
