/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.net.validation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Map;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

/**
 * A versioned envelope for server-derived data kept on disk: {@code {"version": n, "data": ...}}.
 *
 * <p>Loading reads the file under the type's limits, refuses a version newer than
 * {@link #currentVersion} (the local copy is kept), runs each registered migration from the stored
 * version up to the current one, and validates the result against the current schema. Nothing is
 * returned unless every step succeeded, so a caller never replaces valid local state with a newer,
 * unknown, partly valid or oversized record.
 */
public final class VersionedPayload {

    private final String typeId;
    private final int currentVersion;
    private final Map<Integer, UnaryOperator<JsonElement>> migrations = new TreeMap<>();

    /**
     * @param typeId         the registered schema the current {@code data} must match
     * @param currentVersion the version this build writes
     */
    public VersionedPayload(String typeId, int currentVersion) {
        this.typeId = typeId;
        this.currentVersion = currentVersion;
    }

    /** Registers the step from {@code fromVersion} to {@code fromVersion + 1}. */
    public VersionedPayload migration(int fromVersion, UnaryOperator<JsonElement> step) {
        migrations.put(fromVersion, step);
        return this;
    }

    public int currentVersion() {
        return currentVersion;
    }

    /** The validated {@code data} at the current version. */
    public JsonElement load(String fileText) throws ValidationException {
        MessageSchemas.Entry entry = MessageSchemas.entry(typeId);
        if (entry == null) {
            throw new ValidationException(typeId, ValidationException.Kind.UNKNOWN_TYPE, "");
        }
        JsonElement root;
        try {
            root = GuardedJson.parse(fileText, entry.limits());
        } catch (ValidationException invalid) {
            throw invalid.withType(typeId);
        }
        if (!root.isJsonObject()) {
            throw new ValidationException(typeId, ValidationException.Kind.WRONG_TYPE, "$");
        }
        JsonObject envelope = root.getAsJsonObject();
        if (envelope.size() != 2 || !envelope.has("version") || !envelope.has("data")) {
            throw new ValidationException(typeId, ValidationException.Kind.MISSING_FIELD, "$");
        }
        JsonElement versionElement = envelope.get("version");
        int version;
        try {
            Schema.integer(1, Integer.MAX_VALUE).validate(versionElement);
            version = versionElement.getAsInt();
        } catch (ValidationException invalid) {
            throw invalid.withType(typeId);
        }
        if (version > currentVersion) {
            throw new ValidationException(typeId, ValidationException.Kind.UNSUPPORTED_VERSION, "$.version");
        }
        JsonElement data = envelope.get("data");
        for (int v = version; v < currentVersion; v++) {
            UnaryOperator<JsonElement> step = migrations.get(v);
            if (step == null) {
                throw new ValidationException(typeId, ValidationException.Kind.UNSUPPORTED_VERSION, "$.version");
            }
            try {
                data = step.apply(data.deepCopy());
            } catch (RuntimeException failedMigration) {
                throw new ValidationException(typeId, ValidationException.Kind.MALFORMED, "$.data");
            }
        }
        try {
            entry.schema().validate(data);
        } catch (ValidationException invalid) {
            throw invalid.withType(typeId);
        }
        return data;
    }

    /** The envelope for {@code data}, which is checked before anything is written. */
    public String save(JsonElement data) throws ValidationException {
        MessageSchemas.checkOutgoing(typeId, data);
        JsonObject envelope = new JsonObject();
        envelope.add("version", new JsonPrimitive(currentVersion));
        envelope.add("data", data);
        return envelope.toString();
    }
}
