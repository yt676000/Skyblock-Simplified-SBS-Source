/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.net.validation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A declarative description of one JSON value, checked against a tree from {@link GuardedJson}.
 *
 * <p>Objects come in two modes. {@link #object()} is for SBS-defined messages: the field set is
 * fixed and a member the schema does not name is a violation. {@link #relayed()} is for documents
 * relayed from a third party (Hypixel, Mojang): only the fields this client reads are declared and
 * checked, and every other member is ignored, because those documents gain fields without notice.
 *
 * <p>Every check that fails throws a {@link ValidationException} naming the kind and the path; the
 * value itself is never part of the message.
 */
public abstract class Schema {

    /** Strings of printable text: no control characters, no line breaks. */
    public static final Pattern PRINTABLE = Pattern.compile("^[^\\p{Cntrl}\\u2028\\u2029]*$");

    /** Opaque identifiers: ASCII letters, digits and {@code _ - . : ;}. */
    public static final Pattern ID = Pattern.compile("^[A-Za-z0-9_\\-.:;]*$");

    /** Hex digits only, dashes allowed (uuids, hashes). */
    public static final Pattern HEX = Pattern.compile("^[0-9a-fA-F-]*$");

    abstract void check(JsonElement value, String path) throws ValidationException;

    /** Checks {@code value}; the path in a failure starts at {@code $}. */
    public final void validate(JsonElement value) throws ValidationException {
        check(value, "$");
    }

    /** Accepts {@code null} in addition to what this schema accepts. */
    public Schema nullable() {
        Schema inner = this;
        return new Schema() {
            @Override
            void check(JsonElement value, String path) throws ValidationException {
                if (value == null || value.isJsonNull()) {
                    return;
                }
                inner.check(value, path);
            }
        };
    }

    // ---------------------------------------------------------------- scalars

    public static Schema string(int maxLength) {
        return string(maxLength, PRINTABLE);
    }

    public static Schema string(int maxLength, Pattern pattern) {
        return string(0, maxLength, pattern);
    }

    public static Schema string(int minLength, int maxLength, Pattern pattern) {
        return new Schema() {
            @Override
            void check(JsonElement value, String path) throws ValidationException {
                String text = requireString(value, path);
                if (text.length() > maxLength || text.length() < minLength) {
                    throw new ValidationException(null, ValidationException.Kind.STRING_TOO_LONG, path);
                }
                if (pattern != null && !pattern.matcher(text).matches()) {
                    throw new ValidationException(null, ValidationException.Kind.PATTERN, path);
                }
            }
        };
    }

    public static Schema enumOf(String... allowed) {
        Set<String> set = Set.of(allowed);
        return new Schema() {
            @Override
            void check(JsonElement value, String path) throws ValidationException {
                if (!set.contains(requireString(value, path))) {
                    throw new ValidationException(null, ValidationException.Kind.ENUM, path);
                }
            }
        };
    }

    /** A finite number within {@code [min, max]}. */
    public static Schema number(double min, double max) {
        return new Schema() {
            @Override
            void check(JsonElement value, String path) throws ValidationException {
                double number = requireNumber(value, path).doubleValue();
                if (!Double.isFinite(number)) {
                    throw new ValidationException(null, ValidationException.Kind.NOT_FINITE, path);
                }
                if (number < min || number > max) {
                    throw new ValidationException(null, ValidationException.Kind.OUT_OF_RANGE, path);
                }
            }
        };
    }

    /** A whole number within {@code [min, max]}. */
    public static Schema integer(long min, long max) {
        return new Schema() {
            @Override
            void check(JsonElement value, String path) throws ValidationException {
                BigDecimal number = requireNumber(value, path);
                BigDecimal whole;
                try {
                    whole = new BigDecimal(number.toBigIntegerExact());
                } catch (ArithmeticException fractional) {
                    throw new ValidationException(null, ValidationException.Kind.WRONG_TYPE, path);
                }
                if (whole.compareTo(BigDecimal.valueOf(min)) < 0 || whole.compareTo(BigDecimal.valueOf(max)) > 0) {
                    throw new ValidationException(null, ValidationException.Kind.OUT_OF_RANGE, path);
                }
            }
        };
    }

    public static Schema bool() {
        return new Schema() {
            @Override
            void check(JsonElement value, String path) throws ValidationException {
                if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
                    throw new ValidationException(null, ValidationException.Kind.WRONG_TYPE, path);
                }
            }
        };
    }

    /** Any value. Only for parts of a relayed document this client passes through unread. */
    public static Schema any() {
        return new Schema() {
            @Override
            void check(JsonElement value, String path) {
            }
        };
    }

    // ------------------------------------------------------------- composites

    public static Schema array(Schema element, int maxLength) {
        return new Schema() {
            @Override
            void check(JsonElement value, String path) throws ValidationException {
                if (value == null || !value.isJsonArray()) {
                    throw new ValidationException(null, ValidationException.Kind.WRONG_TYPE, path);
                }
                JsonArray array = value.getAsJsonArray();
                if (array.size() > maxLength) {
                    throw new ValidationException(null, ValidationException.Kind.ARRAY_TOO_LARGE, path);
                }
                for (int i = 0; i < array.size(); i++) {
                    element.check(array.get(i), path + "[" + i + "]");
                }
            }
        };
    }

    /** An object used as a map: every key matches {@code keyPattern}, every value {@code value}. */
    public static Schema map(Pattern keyPattern, int maxKeyLength, Schema value, int maxEntries) {
        return new Schema() {
            @Override
            void check(JsonElement element, String path) throws ValidationException {
                if (element == null || !element.isJsonObject()) {
                    throw new ValidationException(null, ValidationException.Kind.WRONG_TYPE, path);
                }
                JsonObject object = element.getAsJsonObject();
                if (object.size() > maxEntries) {
                    throw new ValidationException(null, ValidationException.Kind.MAP_TOO_LARGE, path);
                }
                for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                    String key = entry.getKey();
                    String child = path + "." + GuardedJson.safeName(key);
                    if (key.length() > maxKeyLength || !keyPattern.matcher(key).matches()) {
                        throw new ValidationException(null, ValidationException.Kind.PATTERN, child);
                    }
                    value.check(entry.getValue(), child);
                }
            }
        };
    }

    /** A fixed-field object for SBS-defined messages: unknown members are rejected. */
    public static ObjectSchema object() {
        return new ObjectSchema(true);
    }

    /** A relayed third-party object: only declared members are checked, others are ignored. */
    public static ObjectSchema relayed() {
        return new ObjectSchema(false);
    }

    /** Object schema with required and optional members. */
    public static final class ObjectSchema extends Schema {
        private final boolean closed;
        private final Map<String, Schema> required = new LinkedHashMap<>();
        private final Map<String, Schema> optional = new LinkedHashMap<>();

        private ObjectSchema(boolean closed) {
            this.closed = closed;
        }

        public ObjectSchema required(String name, Schema schema) {
            required.put(name, schema);
            return this;
        }

        /** Absent and {@code null} are both accepted. */
        public ObjectSchema optional(String name, Schema schema) {
            optional.put(name, schema);
            return this;
        }

        @Override
        void check(JsonElement value, String path) throws ValidationException {
            if (value == null || !value.isJsonObject()) {
                throw new ValidationException(null, ValidationException.Kind.WRONG_TYPE, path);
            }
            JsonObject object = value.getAsJsonObject();
            for (Map.Entry<String, Schema> field : required.entrySet()) {
                JsonElement member = object.get(field.getKey());
                if (member == null) {
                    throw new ValidationException(null, ValidationException.Kind.MISSING_FIELD,
                            path + "." + field.getKey());
                }
                field.getValue().check(member, path + "." + field.getKey());
            }
            for (Map.Entry<String, Schema> field : optional.entrySet()) {
                JsonElement member = object.get(field.getKey());
                if (member != null && !member.isJsonNull()) {
                    field.getValue().check(member, path + "." + field.getKey());
                }
            }
            if (closed) {
                for (String name : object.keySet()) {
                    if (!required.containsKey(name) && !optional.containsKey(name)) {
                        throw new ValidationException(null, ValidationException.Kind.UNKNOWN_FIELD,
                                path + "." + GuardedJson.safeName(name));
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    static String requireString(JsonElement value, String path) throws ValidationException {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new ValidationException(null, ValidationException.Kind.WRONG_TYPE, path);
        }
        return value.getAsString();
    }

    static BigDecimal requireNumber(JsonElement value, String path) throws ValidationException {
        if (value == null || !value.isJsonPrimitive()) {
            throw new ValidationException(null, ValidationException.Kind.WRONG_TYPE, path);
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (!primitive.isNumber()) {
            throw new ValidationException(null, ValidationException.Kind.WRONG_TYPE, path);
        }
        try {
            return primitive.getAsBigDecimal();
        } catch (NumberFormatException notFinite) {
            throw new ValidationException(null, ValidationException.Kind.NOT_FINITE, path);
        }
    }
}
