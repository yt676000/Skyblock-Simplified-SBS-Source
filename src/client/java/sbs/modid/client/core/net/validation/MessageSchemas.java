/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.net.validation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;
import sbs.modid.SkyblockSimplifiedSBS;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The registry of every remote message this client accepts, keyed by a stable type id.
 *
 * <p>Remote data reaches feature code only through {@link #parse}: the body is read under the
 * type's {@link JsonLimits} by {@link GuardedJson}, then checked against the type's {@link Schema}.
 * Any violation rejects the whole message; the log line names the type id and the violation kind,
 * never the payload. Outgoing SBS messages are checked with {@link #checkOutgoing} before they are
 * sent.
 *
 * <p>Type ids are stable and never renamed; the definitions live in {@link MessageTypes}, and
 * {@code docs/features/comms-hardening.md} lists them.
 */
public final class MessageSchemas {

    /** A registered type: its schema and its structural limits. */
    public record Entry(String typeId, Schema schema, JsonLimits limits) {
    }

    private static final Map<String, Entry> REGISTRY = new ConcurrentHashMap<>();

    /**
     * Gson for mapping a validated tree onto fixed records and classes: strict, no JDK-unsafe
     * allocation, no polymorphic adapters. Never used on unvalidated input.
     */
    public static final Gson GSON = new GsonBuilder()
            .setStrictness(Strictness.STRICT)
            .disableJdkUnsafe()
            .disableHtmlEscaping()
            .create();

    static {
        MessageTypes.registerAll();
    }

    private MessageSchemas() {
    }

    /** Registers a type. A second registration of the same id is a programming error. */
    public static void register(String typeId, Schema schema, JsonLimits limits) {
        if (REGISTRY.putIfAbsent(typeId, new Entry(typeId, schema, limits)) != null) {
            throw new IllegalStateException("message type registered twice: " + typeId);
        }
    }

    public static Entry entry(String typeId) {
        return REGISTRY.get(typeId);
    }

    public static boolean isRegistered(String typeId) {
        return REGISTRY.containsKey(typeId);
    }

    /** The body cap for a type, for the transport's streaming limit. */
    public static long maxBytes(String typeId) {
        Entry entry = REGISTRY.get(typeId);
        return entry == null ? JsonLimits.DEFAULT.maxBytes() : entry.limits().maxBytes();
    }

    /** Reads and validates a body; returns the checked tree. */
    public static JsonElement parse(String typeId, byte[] body) throws ValidationException {
        Entry entry = require(typeId);
        try {
            JsonElement tree = GuardedJson.parse(body, entry.limits());
            entry.schema().validate(tree);
            return tree;
        } catch (ValidationException invalid) {
            throw reject(invalid.withType(typeId));
        }
    }

    /** Reads and validates a body; returns the checked tree. */
    public static JsonElement parse(String typeId, String body) throws ValidationException {
        Entry entry = require(typeId);
        try {
            JsonElement tree = GuardedJson.parse(body, entry.limits());
            entry.schema().validate(tree);
            return tree;
        } catch (ValidationException invalid) {
            throw reject(invalid.withType(typeId));
        }
    }

    /** Validates and maps a body onto a fixed class. */
    public static <T> T parse(String typeId, String body, Class<T> type) throws ValidationException {
        return map(typeId, parse(typeId, body), type);
    }

    /** Validates and maps a body onto a fixed class. */
    public static <T> T parse(String typeId, byte[] body, Class<T> type) throws ValidationException {
        return map(typeId, parse(typeId, body), type);
    }

    /** Maps an already validated tree onto a fixed class. */
    public static <T> T map(String typeId, JsonElement tree, Class<T> type) throws ValidationException {
        try {
            T value = GSON.fromJson(tree, type);
            if (value == null) {
                throw new ValidationException(typeId, ValidationException.Kind.MALFORMED, "");
            }
            return value;
        } catch (JsonParseException | IllegalStateException | NumberFormatException | ClassCastException wrong) {
            throw reject(new ValidationException(typeId, ValidationException.Kind.WRONG_TYPE, ""));
        }
    }

    /** Checks an outgoing message against its schema; the request is not sent when this throws. */
    public static void checkOutgoing(String typeId, JsonElement message) throws ValidationException {
        Entry entry = require(typeId);
        try {
            entry.schema().validate(message);
            String text = message.toString();
            GuardedJson.parse(text, entry.limits());
        } catch (ValidationException invalid) {
            throw reject(invalid.withType(typeId));
        }
    }

    private static Entry require(String typeId) throws ValidationException {
        Entry entry = REGISTRY.get(typeId);
        if (entry == null) {
            throw reject(new ValidationException(typeId, ValidationException.Kind.UNKNOWN_TYPE, ""));
        }
        return entry;
    }

    /** Logs a rejection (type id, kind and path only) and returns it for throwing. */
    public static ValidationException rejectLogged(ValidationException invalid) {
        return reject(invalid);
    }

    private static ValidationException reject(ValidationException invalid) {
        SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Net] Rejected message {}: {} at {}", invalid.typeId(),
                invalid.kind(), invalid.path().isEmpty() ? "$" : invalid.path());
        return invalid;
    }
}
