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
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.MalformedJsonException;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

/**
 * Reads remote JSON into a Gson tree under {@link JsonLimits}, in strict mode.
 *
 * <p>Compared with {@code JsonParser.parseString} this reader refuses: lenient syntax (comments,
 * single quotes, unquoted names, trailing data), {@code NaN} and {@code Infinity}, numbers that do
 * not fit a finite double, duplicate member names within one object, and anything beyond the
 * configured size, depth, array, member, string and element limits. The first violation aborts the
 * whole document with a {@link ValidationException}; no partial tree is ever returned.
 *
 * <p>Numbers are kept as {@link BigDecimal}, so {@code getAsLong} on an integer is exact.
 */
public final class GuardedJson {

    private GuardedJson() {
    }

    /** Parses UTF-8 bytes. The byte length is checked before any decoding. */
    public static JsonElement parse(byte[] body, JsonLimits limits) throws ValidationException {
        if (body == null) {
            throw new ValidationException(null, ValidationException.Kind.MALFORMED, "");
        }
        if (body.length > limits.maxBytes()) {
            throw new ValidationException(null, ValidationException.Kind.OVERSIZE, "");
        }
        return parseChecked(new String(body, StandardCharsets.UTF_8), limits);
    }

    /** Parses a string; its UTF-8 length counts against {@link JsonLimits#maxBytes()}. */
    public static JsonElement parse(String body, JsonLimits limits) throws ValidationException {
        if (body == null) {
            throw new ValidationException(null, ValidationException.Kind.MALFORMED, "");
        }
        // A char is at most three UTF-8 bytes, so only near-limit bodies need the exact count.
        if ((long) body.length() * 3 > limits.maxBytes()
                && body.getBytes(StandardCharsets.UTF_8).length > limits.maxBytes()) {
            throw new ValidationException(null, ValidationException.Kind.OVERSIZE, "");
        }
        return parseChecked(body, limits);
    }

    private static JsonElement parseChecked(String body, JsonLimits limits) throws ValidationException {
        try (JsonReader reader = new JsonReader(new StringReader(body))) {
            reader.setStrictness(Strictness.STRICT);
            Walker walker = new Walker(reader, limits);
            JsonElement root = walker.read(0);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new ValidationException(null, ValidationException.Kind.MALFORMED, "$");
            }
            return root;
        } catch (ValidationException invalid) {
            throw invalid;
        } catch (MalformedJsonException | IllegalStateException | NumberFormatException malformed) {
            throw new ValidationException(null, ValidationException.Kind.MALFORMED, "");
        } catch (IOException io) {
            // EOFException for truncated input, and other reader failures over an in-memory string.
            throw new ValidationException(null, ValidationException.Kind.MALFORMED, "");
        }
    }

    private static final class Walker {
        private final JsonReader reader;
        private final JsonLimits limits;
        private int elements;
        /** Current location: a member name (String) or an array index (int[1]) per level. */
        private final Object[] segments;
        private int top;

        Walker(JsonReader reader, JsonLimits limits) {
            this.reader = reader;
            this.limits = limits;
            this.segments = new Object[limits.maxDepth() + 1];
        }

        private ValidationException fail(ValidationException.Kind kind) {
            StringBuilder path = new StringBuilder("$");
            for (int i = 0; i < top; i++) {
                Object segment = segments[i];
                if (segment instanceof int[] index) {
                    path.append('[').append(index[0]).append(']');
                } else {
                    path.append('.').append(safeName((String) segment));
                }
            }
            return new ValidationException(null, kind, path.toString());
        }

        JsonElement read(int depth) throws IOException {
            if (++elements > limits.maxElements()) {
                throw fail(ValidationException.Kind.TOO_MANY_ELEMENTS);
            }
            JsonToken token = reader.peek();
            switch (token) {
                case BEGIN_OBJECT -> {
                    if (depth + 1 > limits.maxDepth()) {
                        throw fail(ValidationException.Kind.TOO_DEEP);
                    }
                    reader.beginObject();
                    JsonObject object = new JsonObject();
                    int members = 0;
                    while (reader.hasNext()) {
                        String name = reader.nextName();
                        if (name.length() > limits.maxString()) {
                            throw fail(ValidationException.Kind.STRING_TOO_LONG);
                        }
                        if (++members > limits.maxMembers()) {
                            throw fail(ValidationException.Kind.MAP_TOO_LARGE);
                        }
                        segments[top++] = name;
                        if (object.has(name)) {
                            throw fail(ValidationException.Kind.DUPLICATE_KEY);
                        }
                        object.add(name, read(depth + 1));
                        top--;
                    }
                    reader.endObject();
                    return object;
                }
                case BEGIN_ARRAY -> {
                    if (depth + 1 > limits.maxDepth()) {
                        throw fail(ValidationException.Kind.TOO_DEEP);
                    }
                    reader.beginArray();
                    JsonArray array = new JsonArray();
                    int[] index = {0};
                    segments[top++] = index;
                    while (reader.hasNext()) {
                        if (index[0] >= limits.maxArray()) {
                            throw fail(ValidationException.Kind.ARRAY_TOO_LARGE);
                        }
                        array.add(read(depth + 1));
                        index[0]++;
                    }
                    top--;
                    reader.endArray();
                    return array;
                }
                case STRING -> {
                    String value = reader.nextString();
                    if (value.length() > limits.maxString()) {
                        throw fail(ValidationException.Kind.STRING_TOO_LONG);
                    }
                    return new JsonPrimitive(value);
                }
                case NUMBER -> {
                    String literal = reader.nextString();
                    if (literal.length() > 64) {
                        throw fail(ValidationException.Kind.OUT_OF_RANGE);
                    }
                    double asDouble = Double.parseDouble(literal);
                    if (!Double.isFinite(asDouble)) {
                        throw fail(ValidationException.Kind.NOT_FINITE);
                    }
                    return new JsonPrimitive(new BigDecimal(literal));
                }
                case BOOLEAN -> {
                    return new JsonPrimitive(reader.nextBoolean());
                }
                case NULL -> {
                    reader.nextNull();
                    return JsonNull.INSTANCE;
                }
                default -> throw fail(ValidationException.Kind.MALFORMED);
            }
        }
    }

    /** Member names enter log paths, so only a bounded, printable form of them is kept. */
    static String safeName(String name) {
        StringBuilder out = new StringBuilder(Math.min(name.length(), 32));
        for (int i = 0; i < name.length() && out.length() < 32; i++) {
            char c = name.charAt(i);
            out.append((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_'
                    || c == '-' ? c : '?');
        }
        return out.toString();
    }
}
