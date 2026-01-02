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

package de.linzn.mirra.openai.models;

import com.azure.ai.openai.models.FunctionDefinition;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * This is the property for creating the 'FunctionDefinition'.
 *
 * @see FunctionParameters
 * @see FunctionDefinition
 */
@JsonIgnoreProperties(value = {"required"})
public class FunctionProperties {
    // Type of Property
    private String type;
    // Description of the Property
    private String description;
    // Name of the Property
    private String name;

    private FunctionProperties items;
    // Enum values for the Property
    @JsonProperty("enum")
    private List<String> enumString;

    private boolean required = false;

    /**
     * Get type of property.
     *
     * @return Type of property.
     */
    public String getType() {
        return type;
    }

    /**
     * Set type of property.
     *
     * @param type type of property.
     * @return Object itself.
     */
    public FunctionProperties setType(String type) {
        this.type = type;
        return this;
    }

    /**
     * Get name of property.
     *
     * @return Name of property.
     */
    public String getName() {
        return name;
    }


    public FunctionProperties setName(String name) {
        this.name = name;
        return this;
    }

    /**
     * Get description of property.
     *
     * @return Description of property.
     */
    public String getDescription() {
        return description;
    }


    /**
     * Set description of property.
     *
     * @param description description of property.
     * @return Object itself.
     */
    public FunctionProperties setDescription(String description) {
        this.description = description;
        return this;
    }

    /**
     * Get boolean value if it`s a required property
     *
     * @return Boolean value if it`s a required property
     */
    public boolean isRequired() {
        return this.required;
    }

    /**
     * Set boolean value if this property is required
     *
     * @param required boolean value if this property is required
     * @return Object itself.
     */
    public FunctionProperties setRequired(boolean required) {
        this.required = required;
        return this;
    }

    /**
     * Get enum values for the property.
     *
     * @return Enum values for the property.
     */
    public List<String> getEnumString() {
        return enumString;
    }

    /**
     * Set enum values for the property.
     *
     * @param enumString enum values for the property.
     * @return Object itself.
     */
    public FunctionProperties setEnumString(List<String> enumString) {
        this.enumString = enumString;
        return this;
    }

    public FunctionProperties getItems() {
        return this.items;
    }

    public FunctionProperties setItems(FunctionProperties items) {
        this.items = items;
        return this;
    }

}