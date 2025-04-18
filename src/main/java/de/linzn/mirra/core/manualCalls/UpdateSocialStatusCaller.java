package de.linzn.mirra.core.manualCalls;

import de.linzn.mirra.MirraPlugin;
import de.linzn.mirra.identitySystem.TokenSource;
import de.linzn.mirra.identitySystem.UserToken;
import de.linzn.mirra.openai.IFunctionCall;
import de.linzn.openJL.pairs.Pair;
import de.stem.stemSystem.STEMSystemApp;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;


public class UpdateSocialStatusCaller extends ManualFunctionCaller {


    @Override
    public void call() {
        STEMSystemApp.LOGGER.CORE("Change social status trigger");
        UserToken userToken = MirraPlugin.mirraPlugin.getIdentityManager().getOrCreateUserToken("stem_internal_handler", TokenSource.INTERNAL);
        IFunctionCall iFunction = MirraPlugin.mirraPlugin.getAiManager().getFunctionProvider().getFunction("trigger_manual_function_call");
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("manual_function_call_name", "set_mirra_status");
        jsonObject.put("request_description", "Change you social status in discord/whatsapp. Think by yourself what you want for a status. The status should be in german. Maybe something with the topic MirraNET or STEM and you are in control about it. To change the status use the function 'set_mirra_status'");
        MirraPlugin.mirraPlugin.getAiManager().getDefaultModel().requestStandaloneFunctionCall(iFunction.functionName(), jsonObject, userToken);
    }

    @Override
    public String repeatCronString() {
        return "0 */6 * * *";
    }

    @Override
    public Pair<Integer, TimeUnit> delayedStart() {
        return new Pair<>(1, TimeUnit.MINUTES);
    }
}
