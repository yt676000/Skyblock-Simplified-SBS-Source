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
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;

/**
 * Bounded JSON reader for relayed Hypixel bodies. Builds a Gson tree while enforcing, during the
 * read, the limits the Player Viewer promises: total size, nesting depth, array and object sizes,
 * per-string length, strict syntax (no {@code NaN}, no comments, no unquoted strings) and no
 * duplicate keys. Any breach throws {@link PvInvalidDataException}; nothing partial is returned.
 *
 * <p>The tree is an intermediate only. {@link PvParser} copies the fields the viewer reads into
 * fixed records and validates them; every other field is dropped there.
 */
public final class PvJson {

    /** Largest relayed body accepted (a full coop profile with every member is a few MiB). */
    public static final int MAX_BODY_CHARS = 32 << 20;
    public static final int MAX_DEPTH = 64;
    public static final int MAX_ARRAY = 50_000;
    public static final int MAX_OBJECT = 50_000;
    /** Strings carry base64 inventory blobs, so the generic cap is the blob cap plus slack. */
    public static final int MAX_STRING = 1_500_000;

    private PvJson() {
    }

    public static JsonElement parse(String body) throws PvInvalidDataException {
        if (body == null) {
            throw new PvInvalidDataException("empty body");
        }
        if (body.length() > MAX_BODY_CHARS) {
            throw new PvInvalidDataException("body too large");
        }
        try (JsonReader reader = new JsonReader(new StringReader(body))) {
            reader.setStrictness(Strictness.STRICT);
            reader.setNestingLimit(MAX_DEPTH);
            JsonElement root = read(reader, 1);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new PvInvalidDataException("trailing data");
            }
            return root;
        } catch (PvInvalidDataException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new PvInvalidDataException("malformed json");
        }
    }

    private static JsonElement read(JsonReader reader, int depth) throws IOException, PvInvalidDataException {
        if (depth > MAX_DEPTH) {
            throw new PvInvalidDataException("too deep");
        }
        switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                JsonObject obj = new JsonObject();
                reader.beginObject();
                int n = 0;
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (key.length() > 1024) {
                        throw new PvInvalidDataException("key too long");
                    }
                    if (++n > MAX_OBJECT) {
                        throw new PvInvalidDataException("object too large");
                    }
                    if (obj.has(key)) {
                        throw new PvInvalidDataException("duplicate key");
                    }
                    obj.add(key, read(reader, depth + 1));
                }
                reader.endObject();
                return obj;
            }
            case BEGIN_ARRAY -> {
                JsonArray arr = new JsonArray();
                reader.beginArray();
                int n = 0;
                while (reader.hasNext()) {
                    if (++n > MAX_ARRAY) {
                        throw new PvInvalidDataException("array too large");
                    }
                    arr.add(read(reader, depth + 1));
                }
                reader.endArray();
                return arr;
            }
            case STRING -> {
                String s = reader.nextString();
                if (s.length() > MAX_STRING) {
                    throw new PvInvalidDataException("string too long");
                }
                return new JsonPrimitive(s);
            }
            case NUMBER -> {
                String raw = reader.nextString();
                if (raw.length() > 64) {
                    throw new PvInvalidDataException("number too long");
                }
                return new JsonPrimitive(new java.math.BigDecimal(raw));
            }
            case BOOLEAN -> {
                return new JsonPrimitive(reader.nextBoolean());
            }
            case NULL -> {
                reader.nextNull();
                return JsonNull.INSTANCE;
            }
            default -> throw new PvInvalidDataException("unexpected token");
        }
    }
}
