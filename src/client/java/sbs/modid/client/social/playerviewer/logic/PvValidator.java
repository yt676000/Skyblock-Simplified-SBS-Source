/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.playerviewer.logic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Typed, bounds-checked access to the fields of a relayed Hypixel tree. This is the single place
 * where a remote value becomes a Java value, so every rule lives here:
 *
 * <ul>
 *   <li>absent or {@code null} -> {@code null} (the page then shows "n/a");</li>
 *   <li>present with the wrong type -> {@link PvInvalidDataException}; {@link PvParser} turns that
 *       into "this section is unavailable" and keeps the other sections;</li>
 *   <li>numbers must be finite and within {@link #MAX_ABS};</li>
 *   <li>strings must be within the per-field length cap the caller names.</li>
 * </ul>
 *
 * <p>Inside free-form counter maps (kills, collections, rabbits...) a non-numeric entry is not a
 * field the viewer reads and is skipped, exactly as the old server skipped it; a numeric entry is
 * still bounds-checked.
 */
public final class PvValidator {

    /** Largest magnitude any counter may have. Real values stay below 1e13. */
    public static final double MAX_ABS = 1e16;
    public static final int ID_LEN = 128;
    public static final int NAME_LEN = 256;

    private PvValidator() {
    }

    public static Double num(JsonObject o, String key) throws PvInvalidDataException {
        if (o == null) {
            return null;
        }
        try {
            return num(o.get(key));
        } catch (PvInvalidDataException e) {
            throw new PvInvalidDataException(key + ": " + e.getMessage());
        }
    }

    public static Double num(JsonElement e) throws PvInvalidDataException {
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) {
            return e.getAsBoolean() ? 1.0 : 0.0;
        }
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw new PvInvalidDataException("expected number, got " + typeOf(e));
        }
        return checked(e.getAsJsonPrimitive());
    }

    private static double checked(JsonPrimitive p) throws PvInvalidDataException {
        double d = p.getAsNumber().doubleValue();
        if (!Double.isFinite(d) || Math.abs(d) > MAX_ABS) {
            throw new PvInvalidDataException("expected finite number in range, got out-of-range number");
        }
        return d;
    }

    /**
     * For fields Hypixel is known to have changed shape (a number became an object): a non-number
     * is treated as absent, exactly as the old server's {@code isinstance} guard did. A number is
     * still bounds-checked.
     */
    public static Double numIfNumber(JsonObject o, String key) throws PvInvalidDataException {
        return o != null && isNumber(o.get(key)) ? num(o, key) : null;
    }

    /** True when {@code e} is a JSON number (the old server's {@code isinstance(v, (int, float))}, which in Python
     * also accepts booleans as 1 / 0). */
    public static boolean isNumber(JsonElement e) {
        return e != null && e.isJsonPrimitive()
                && (e.getAsJsonPrimitive().isNumber() || e.getAsJsonPrimitive().isBoolean());
    }

    public static String str(JsonObject o, String key, int maxLen) throws PvInvalidDataException {
        if (o == null) {
            return null;
        }
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            throw new PvInvalidDataException(key + ": expected string, got " + typeOf(e));
        }
        String s = e.getAsString();
        if (s.length() > maxLen) {
            throw new PvInvalidDataException(key + ": expected string of at most " + maxLen + " chars, got longer string");
        }
        return s;
    }

    public static Boolean bool(JsonObject o, String key) throws PvInvalidDataException {
        if (o == null) {
            return null;
        }
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
            throw new PvInvalidDataException(key + ": expected boolean, got " + typeOf(e));
        }
        return e.getAsBoolean();
    }

    public static JsonObject obj(JsonObject o, String key) throws PvInvalidDataException {
        if (o == null) {
            return null;
        }
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (!e.isJsonObject()) {
            throw new PvInvalidDataException(key + ": expected object, got " + typeOf(e));
        }
        return e.getAsJsonObject();
    }

    /** Like {@link #obj} but returns {@code null} instead of failing for a non-object value. */
    public static JsonObject objIfObject(JsonObject o, String key) {
        if (o == null) {
            return null;
        }
        JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    public static JsonArray arr(JsonObject o, String key) throws PvInvalidDataException {
        if (o == null) {
            return null;
        }
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (!e.isJsonArray()) {
            throw new PvInvalidDataException(key + ": expected array, got " + typeOf(e));
        }
        return e.getAsJsonArray();
    }

    /** Size of an array field, or {@code null} when absent; wrong type fails. */
    public static Integer count(JsonObject o, String key) throws PvInvalidDataException {
        JsonArray a = arr(o, key);
        return a == null ? null : a.size();
    }

    /** The numeric entries of an object, in document order; {@code null} when absent. */
    public static Map<String, Double> numMap(JsonObject o, String key) throws PvInvalidDataException {
        try {
            return numMap(obj(o, key));
        } catch (PvInvalidDataException e) {
            throw e.getMessage().startsWith(key + ":") ? e : new PvInvalidDataException(key + "." + e.getMessage());
        }
    }

    public static Map<String, Double> numMap(JsonObject m) throws PvInvalidDataException {
        if (m == null) {
            return null;
        }
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : m.entrySet()) {
            if (isNumber(e.getValue())) {
                try {
                    out.put(e.getKey(), num(e.getValue()));
                } catch (PvInvalidDataException ex) {
                    throw new PvInvalidDataException(e.getKey() + ": " + ex.getMessage());
                }
            }
        }
        return out;
    }

    /** The JSON type name of {@code e}, for violation messages (never the value itself). */
    public static String typeOf(JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return "null";
        }
        if (e.isJsonObject()) {
            return "object";
        }
        if (e.isJsonArray()) {
            return "array";
        }
        JsonPrimitive p = e.getAsJsonPrimitive();
        return p.isBoolean() ? "boolean" : p.isNumber() ? "number" : "string";
    }

    /** Size of a JSON object or array field, or {@code null}; used for "is it non-empty" checks. */
    public static boolean nonEmptyObject(JsonObject o) {
        return o != null && !o.isEmpty();
    }
}
