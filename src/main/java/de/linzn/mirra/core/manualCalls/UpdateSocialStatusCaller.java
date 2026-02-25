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

package de.linzn.mirra.core.manualCalls;

import de.linzn.mirra.MirraPlugin;
import de.linzn.mirra.identitySystem.TokenSource;
import de.linzn.mirra.identitySystem.UserToken;
import de.linzn.mirra.openai.IFunctionCall;
import de.linzn.openJL.pairs.Pair;
import de.linzn.stem.STEMApp;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;


public class UpdateSocialStatusCaller extends ManualFunctionCaller {


    @Override
    public void call() {
        STEMApp.LOGGER.CORE("Change social status trigger");
        UserToken userToken = MirraPlugin.mirraPlugin.getIdentityManager().getOrCreateUserToken("stem_internal_handler", TokenSource.INTERNAL);
        IFunctionCall iFunction = MirraPlugin.mirraPlugin.getAiManager().getFunctionProvider().getFunction("trigger_manual_function_call");
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("manual_function_call_name", "set_mirra_status");
        jsonObject.put("request_description", "Change you social status in discord/whatsapp. Think by yourself what you want for a status. The status should be in german. Maybe something with the topic MirraNET or STEM and you are in control about it. To change the status use the function 'set_mirra_status'");
        MirraPlugin.mirraPlugin.getAiManager().getDefaultModel().requestStandaloneFunctionCall(iFunction.functionName(), jsonObject, userToken);
    }

    @Override
    public String repeatCronString() {
        return "0 8 */2 * *";
    }

    @Override
    public Pair<Integer, TimeUnit> delayedStart() {
        return new Pair<>(1, TimeUnit.MINUTES);
    }
}
