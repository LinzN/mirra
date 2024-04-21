package de.linzn.mirra.core.functions;

import com.theokanning.openai.completion.chat.ChatFunctionDynamic;

import java.util.*;

public class FunctionProvider {
    private final HashMap<String, IFunction> functionHashMap;
    public FunctionProvider(){
        this.functionHashMap = new HashMap<>();
        this.loadInternalFunctions();
    }

    private void loadInternalFunctions(){
        this.registerFunction(new StemStatus());
        this.registerFunction(new SwitchLight());
        this.registerFunction(new StatusLight());
        this.registerFunction(new CreateImage());
        this.registerFunction(new EventTrigger());
    }


    private void registerFunction(IFunction iFunction){
        this.functionHashMap.put(iFunction.functionName().toLowerCase(), iFunction);
    }

    public IFunction getFunction(String functionName){
        return this.functionHashMap.get(functionName.toLowerCase());
    }

    public boolean hasFunction(String functionName){
        return this.functionHashMap.containsKey(functionName.toLowerCase());
    }

    public List<ChatFunctionDynamic> buildChatFunctionProvider(){
        List<ChatFunctionDynamic> functionDynamics = new ArrayList<>();
        for(IFunction iFunction : this.functionHashMap.values()){
            functionDynamics.add(iFunction.getFunctionString());
        }
        return functionDynamics;
    }
}
