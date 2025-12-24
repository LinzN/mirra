/*
 * Copyright (c) 2025 MirraNET, Niklas Linz. All rights reserved.
 *
 * This file is part of the MirraNET project and is licensed under the
 * GNU Lesser General Public License v3.0 (LGPLv3).
 *
 * You may use, distribute and modify this code under the terms
 * of the LGPLv3 license. You should have received a copy of the
 * license along with this file. If not, see <https://www.gnu.org/licenses/lgpl-3.0.html>
 * or contact: niklas.linz@mirranet.de
 */

package de.linzn.mirra.core.functions;

import com.azure.ai.openai.models.FunctionDefinition;
import de.linzn.mirra.core.functions.memory.AccessLongTermMEMORY;
import de.linzn.mirra.core.functions.memory.WriteLongTermMEMORY;
import de.linzn.mirra.core.manualCalls.ManualFunctionCaller;
import de.linzn.mirra.core.manualCalls.UpdateSocialStatusCaller;
import de.linzn.mirra.openai.IFunctionCall;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

public class FunctionProvider {
    private final HashMap<String, IFunctionCall> functionHashMap;
    private final HashSet<ManualFunctionCaller> manualFunctionCallerSet;

    public FunctionProvider() {
        this.functionHashMap = new HashMap<>();
        this.manualFunctionCallerSet = new HashSet<>();
        this.loadInternalFunctions();
        this.loadManualFunctionCaller();
    }

    private void loadInternalFunctions() {
        this.registerFunction(new StemStatus());
        this.registerFunction(new SwitchLight());
        this.registerFunction(new StatusLight());
        this.registerFunction(new CreateImage());
        this.registerFunction(new EventTrigger());
        this.registerFunction(new ReminderTrigger());
        this.registerFunction(new CreateReminder());
        this.registerFunction(new AccessLongTermMEMORY());
        this.registerFunction(new WriteLongTermMEMORY());
        this.registerFunction(new Weather());
        this.registerFunction(new UpdateMirraStatus());
        this.registerFunction(new ManualCallTrigger());
    }

    private void loadManualFunctionCaller() {
        this.manualFunctionCallerSet.add(new UpdateSocialStatusCaller());
    }


    private void registerFunction(IFunctionCall iFunction) {
        this.functionHashMap.put(iFunction.functionName().toLowerCase(), iFunction);
    }


    public IFunctionCall getFunction(String functionName) {
        return this.functionHashMap.get(functionName.toLowerCase());
    }

    public boolean hasFunction(String functionName) {
        return this.functionHashMap.containsKey(functionName.toLowerCase());
    }

    public List<FunctionDefinition> buildChatFunctionProvider() {
        List<FunctionDefinition> functionDynamics = new ArrayList<>();
        for (IFunctionCall iFunction : this.functionHashMap.values()) {
            functionDynamics.add(iFunction.getFunctionString());
        }
        return functionDynamics;
    }

    public JSONObject functionNotFound() {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("success", false);
        jsonObject.put("reason", "No function found with this name!");
        return jsonObject;
    }
}
