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

package de.linzn.mirra.agent.tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collection;

/**
 * Builds JSON schemas for strict function calling: every property is required, optional values are
 * nullable and objects allow no additional properties.
 */
public final class Schema {

    private final JSONObject properties = new JSONObject();
    private final JSONArray required = new JSONArray();

    private Schema() {
    }

    public static Schema object() {
        return new Schema();
    }

    public static JSONObject empty() {
        return object().build();
    }

    public static JSONObject string(String description) {
        return new JSONObject().put("type", "string").put("description", description);
    }

    public static JSONObject integer(String description) {
        return new JSONObject().put("type", "integer").put("description", description);
    }

    public static JSONObject number(String description) {
        return new JSONObject().put("type", "number").put("description", description);
    }

    public static JSONObject bool(String description) {
        return new JSONObject().put("type", "boolean").put("description", description);
    }

    public static JSONObject enumeration(String description, Collection<String> values) {
        return new JSONObject().put("type", "string").put("description", description).put("enum", new JSONArray(values));
    }

    public static JSONObject array(String description, JSONObject items) {
        return new JSONObject().put("type", "array").put("description", description).put("items", items);
    }

    /**
     * Makes a property optional: the model sends {@code null} if it has no value.
     */
    public static JSONObject nullable(JSONObject property) {
        JSONObject copy = new JSONObject(property.toString());
        Object type = copy.get("type");
        copy.put("type", new JSONArray().put(type).put("null"));
        if (copy.has("enum")) {
            copy.getJSONArray("enum").put(JSONObject.NULL);
        }
        return copy;
    }

    public Schema prop(String name, JSONObject property) {
        this.properties.put(name, property);
        this.required.put(name);
        return this;
    }

    public JSONObject build() {
        return new JSONObject()
                .put("type", "object")
                .put("properties", this.properties)
                .put("required", this.required)
                .put("additionalProperties", false);
    }

    /**
     * @return the value or null if the argument is missing or JSON null
     */
    public static String optString(JSONObject arguments, String name) {
        return arguments.isNull(name) ? null : arguments.optString(name, null);
    }

    public static Integer optInt(JSONObject arguments, String name) {
        if (arguments.isNull(name)) {
            return null;
        }
        Object value = arguments.opt(name);
        return value instanceof Number number ? number.intValue() : null;
    }

    public static Double optDouble(JSONObject arguments, String name) {
        if (arguments.isNull(name)) {
            return null;
        }
        Object value = arguments.opt(name);
        return value instanceof Number number ? number.doubleValue() : null;
    }

    public static Boolean optBool(JSONObject arguments, String name) {
        if (arguments.isNull(name)) {
            return null;
        }
        Object value = arguments.opt(name);
        return value instanceof Boolean b ? b : null;
    }

    /**
     * @throws IllegalArgumentException if the value is missing or blank
     */
    public static String requireString(JSONObject arguments, String name) {
        String value = optString(arguments, name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Argument '" + name + "' is missing");
        }
        return value.trim();
    }
}
