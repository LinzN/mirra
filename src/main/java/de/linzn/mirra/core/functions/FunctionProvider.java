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
