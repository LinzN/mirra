package de.linzn.mirra.core;

import de.linzn.mirra.MirraPlugin;
import de.linzn.mirra.core.functions.FunctionProvider;
import de.linzn.mirra.identitySystem.TokenSource;
import de.linzn.mirra.identitySystem.UserToken;
import de.linzn.mirra.openai.IFunctionCall;
import de.stem.stemSystem.STEMSystemApp;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;

public class AIManager {

    private final String openAIToken;
    private final String defaultModelName;
    private final String defaultIdentityName;
    private final FunctionProvider functionProvider;
    private final HashMap<String, AIModel> models;


    public AIManager() {
        this.models = new HashMap<>();
        this.functionProvider = new FunctionProvider();
        this.defaultModelName = MirraPlugin.mirraPlugin.getDefaultConfig().getString("default.model", "mirra");
        this.defaultIdentityName = MirraPlugin.mirraPlugin.getDefaultConfig().getString("default.identityName", "Niklas");
        this.openAIToken = MirraPlugin.mirraPlugin.getDefaultConfig().getString("openAI.token", "xxxx");
        MirraPlugin.mirraPlugin.getDefaultConfig().save();
        this.models.put(this.defaultModelName, new AIModel(this.defaultModelName, this.openAIToken, this));
        this.registerStemAIEventService();
    }

    private void registerStemAIEventService() {
        if (this.functionProvider.hasFunction("trigger_event")) {
            IFunctionCall iFunction = this.functionProvider.getFunction("trigger_event");
            STEMSystemApp.getInstance().getInformationModule().registerAiTextEngine(event -> {
                UserToken userToken = MirraPlugin.mirraPlugin.getIdentityManager().getOrCreateUserToken("stem_internal_handler", TokenSource.INTERNAL);
                try {
                    JSONObject jsonObject = new JSONObject();
                    jsonObject.put("sender", MirraPlugin.mirraPlugin.getAiManager().getDefaultIdentityName());
                    jsonObject.put("event", event);
                    jsonObject.put("outputLanguage", "German");
                    jsonObject.put("source", userToken.getSource().name());
                    jsonObject.put("timestamp", new SimpleDateFormat("dd.MM.yyyy - HH:mm:ss").format(new Date()));
                    return MirraPlugin.mirraPlugin.getAiManager().getDefaultModel().requestStandaloneFunctionCall(iFunction.functionName(), jsonObject, userToken);
                } catch (Exception e) {
                    STEMSystemApp.LOGGER.ERROR(e);
                    return event;
                }
            });
        } else {
            STEMSystemApp.LOGGER.ERROR("Not possible to register ai event system. No function found!");
        }
    }


    public AIModel getDefaultModel() {
        return this.models.get(this.defaultModelName);
    }

    public AIModel getModel(String modelName) {
        return this.models.get(modelName);
    }

    public FunctionProvider getFunctionProvider() {
        return functionProvider;
    }

    public String getDefaultIdentityName() {
        return defaultIdentityName;
    }
}
