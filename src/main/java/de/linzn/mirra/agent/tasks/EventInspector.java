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

package de.linzn.mirra.agent.tasks;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.time.temporal.TemporalAccessor;
import java.util.*;

/**
 * Turns any STEM event into JSON so Mirra can read it and tasks can filter on its fields.
 * Records are read through their components, other objects through public getters without parameters.
 * Nested objects are described one level deep; collections and unknown types become text.
 */
public final class EventInspector {

    private static final int MAX_DEPTH = 2;
    private static final int MAX_FIELDS = 40;
    private static final int MAX_TEXT = 300;
    private static final Set<String> SKIPPED = Set.of("getClass", "isCanceled", "isCancelable", "getName", "hashCode", "toString");

    private EventInspector() {
    }

    public static JSONObject describe(Object event) {
        return object(event, 0);
    }

    private static JSONObject object(Object value, int depth) {
        JSONObject json = new JSONObject();
        Class<?> type = value.getClass();
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                if (json.length() >= MAX_FIELDS) {
                    break;
                }
                try {
                    Method accessor = component.getAccessor();
                    accessor.setAccessible(true);
                    json.put(component.getName(), convert(accessor.invoke(value), depth));
                } catch (Throwable ignored) {
                    /* unreadable component */
                }
            }
            return json;
        }
        Method[] methods = type.getMethods();
        Arrays.sort(methods, Comparator.comparing(Method::getName));
        for (Method method : methods) {
            if (json.length() >= MAX_FIELDS) {
                break;
            }
            String name = method.getName();
            if (method.getParameterCount() != 0 || Modifier.isStatic(method.getModifiers()) || SKIPPED.contains(name)
                    || method.getReturnType() == void.class || method.getDeclaringClass() == Object.class) {
                continue;
            }
            String property;
            if (name.startsWith("get") && name.length() > 3) {
                property = Character.toLowerCase(name.charAt(3)) + name.substring(4);
            } else if (name.startsWith("is") && name.length() > 2 && (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class)) {
                property = Character.toLowerCase(name.charAt(2)) + name.substring(3);
            } else {
                continue;
            }
            /* nested objects: only simple values, so no getter with side effects on big objects is called */
            if (depth > 0 && !isSimple(method.getReturnType())) {
                continue;
            }
            try {
                json.put(property, convert(method.invoke(value), depth));
            } catch (Throwable ignored) {
                /* getters may throw, e.g. while a device is not initialized */
            }
        }
        return json;
    }

    private static Object convert(Object value, int depth) {
        if (value == null) {
            return JSONObject.NULL;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof CharSequence || value instanceof Enum<?> || value instanceof UUID) {
            return text(value.toString());
        }
        if (value instanceof Date date) {
            return date.toInstant().toString();
        }
        if (value instanceof TemporalAccessor) {
            return value.toString();
        }
        if (value instanceof JSONObject object) {
            return text(object.toString());
        }
        if (value instanceof Collection<?> || value instanceof Map<?, ?> || value instanceof JSONArray || value.getClass().isArray()) {
            return text(value instanceof Object[] array ? Arrays.toString(array) : value.toString());
        }
        if (depth + 1 < MAX_DEPTH) {
            return object(value, depth + 1);
        }
        return text(value.toString());
    }

    private static boolean isSimple(Class<?> type) {
        return type.isPrimitive() || Number.class.isAssignableFrom(type) || type == Boolean.class || type == String.class
                || type.isEnum() || Date.class.isAssignableFrom(type) || TemporalAccessor.class.isAssignableFrom(type);
    }

    private static String text(String value) {
        return value.length() > MAX_TEXT ? value.substring(0, MAX_TEXT) + "..." : value;
    }
}
