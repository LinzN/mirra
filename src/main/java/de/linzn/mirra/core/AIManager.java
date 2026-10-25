/*
 * Copyright (c) 2026 MirraNET, Niklas Linz. All rights reserved.
 *
 * This file is part of the MirraNET project and is licensed under the
 * GNU Lesser General Public License v3.0 (LGPLv3).
 *
 * You may use, distribute and modify this code under the terms
 * of the LGPLv3 license. You should have received a copy of the
 * license along with this file. If not, see <https://www.gnu.org/licenses/lgpl-3.0.html>
 * or contact: niklas.linz@mirranet.de
 */

package de.linzn.mirra.core;

import de.linzn.mirra.MirraPlugin;
import de.linzn.mirra.core.functions.FunctionProvider;
import de.linzn.mirra.identitySystem.TokenSource;
import de.linzn.mirra.identitySystem.UserToken;
import de.linzn.mirra.openai.IFunctionCall;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AIManager {

    private final MirraPlugin plugin;
    private final String openAIToken;
    private final String defaultModelName;
    private final String defaultIdentityName;
    private final FunctionProvider functionProvider;
    private final Map<String, AIModel> models;

    public AIManager(MirraPlugin plugin) {
        this.plugin = plugin;
        this.models = new ConcurrentHashMap<>();
        this.functionProvider = new FunctionProvider();
        this.defaultModelName = plugin.getDefaultConfig().getString("default.model", "mirra");
        this.defaultIdentityName = plugin.getDefaultConfig().getString("default.identityName", "Niklas");
        this.openAIToken = plugin.getDefaultConfig().getString("openAI.token", "xxxx");
        plugin.getDefaultConfig().save();
        this.models.put(this.defaultModelName, new AIModel(plugin, this.defaultModelName, this.openAIToken, this));
        this.registerStemAIEventService();
    }

    /**
     * Starts the scheduled manual function calls. Called once all managers exist.
     */
    public void startManualFunctionCallers() {
        this.functionProvider.startManualFunctionCallers(this.plugin);
    }

    private void registerStemAIEventService() {
        if (!this.functionProvider.hasFunction("trigger_event")) {
            this.plugin.getPluginLogger().error("Not possible to register ai event system. No function found!");
            return;
        }
        IFunctionCall iFunction = this.functionProvider.getFunction("trigger_event");
        this.plugin.getStemApi().information().registerAiTextEngine(event -> {
            try {
                UserToken userToken = this.plugin.getIdentityManager().getOrCreateUserToken("stem_internal_handler", TokenSource.INTERNAL);
                JSONObject jsonObject = new JSONObject();
                jsonObject.put("sender", this.getDefaultIdentityName());
                jsonObject.put("event", event);
                jsonObject.put("outputLanguage", "German");
                jsonObject.put("source", userToken.getSource().name());
                jsonObject.put("timestamp", new SimpleDateFormat("dd.MM.yyyy - HH:mm:ss").format(new Date()));
                String text = this.getDefaultModel().requestStandaloneFunctionCall(iFunction.functionName(), jsonObject, userToken);
                return text != null ? text : event;
            } catch (Exception e) {
                this.plugin.getPluginLogger().error("AI text engine failed", e);
                return event;
            }
        });
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
