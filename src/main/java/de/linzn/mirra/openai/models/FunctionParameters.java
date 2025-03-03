package de.linzn.mirra.openai.models;


import com.azure.ai.openai.models.FunctionDefinition;
import com.azure.core.util.BinaryData;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This class can be used in FunctionDefinition.
 *
 * @see FunctionProperties
 * @see FunctionDefinition
 */
public class FunctionParameters {
    /* Type of Parameter */
    private String type;

    /* Properties of the parameter */
    private Map<String, FunctionProperties> properties = new HashMap<>();

    /* Required properties */
    @JsonProperty(value = "required")
    private List<String> requiredPropertyNames;

    /**
     * Get type of parameter.
     *
     * @return Type of parameter.
     */
    public String getType() {
        return type;
    }

    /**
     * Set type of parameter.
     *
     * @param type Type of parameter.
     * @return Object itself.
     */
    public FunctionParameters setType(String type) {
        this.type = type;
        return this;
    }

    /**
     * Get properties of parameter.
     *
     * @return All properties for parameter.
     */
    public Map<String, FunctionProperties> getProperties() {
        return properties;
    }


    public FunctionParameters addProperty(FunctionProperties property) {
        this.properties.put(property.getName(), property);
        return this;
    }


    /**
     * Build FunctionParameters to BinaryData
     *
     * @return FunctionParameters as BinaryData
     */
    public BinaryData build() {
        if (this.requiredPropertyNames == null) {
            this.requiredPropertyNames = new ArrayList<>();
        }

        for (FunctionProperties property : this.properties.values()) {
            if (property.isRequired()) {
                this.requiredPropertyNames.add(property.getName());
            }
        }
        return BinaryData.fromObject(this);
    }
}