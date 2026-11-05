/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.net.validation;

import java.io.IOException;

/**
 * A remote message did not match its schema or its limits. The whole message is refused.
 *
 * <p>The message text carries the type id, the violation kind and a field path, never the payload,
 * so it is safe to log. Players see {@link #USER_MESSAGE} instead.
 *
 * <p>Extends {@link IOException} so every network caller that already treats an {@code IOException}
 * as "the request did not work" degrades the same way for invalid data.
 */
public final class ValidationException extends IOException {

    /** The neutral text shown to players for any validation failure. */
    public static final String USER_MESSAGE = "SBS server returned invalid data";

    /** What kind of rule was broken. Stable names: they appear in logs and tests. */
    public enum Kind {
        OVERSIZE,
        TOO_DEEP,
        TOO_MANY_ELEMENTS,
        ARRAY_TOO_LARGE,
        MAP_TOO_LARGE,
        STRING_TOO_LONG,
        MALFORMED,
        DUPLICATE_KEY,
        WRONG_TYPE,
        MISSING_FIELD,
        UNKNOWN_FIELD,
        NOT_FINITE,
        OUT_OF_RANGE,
        PATTERN,
        ENUM,
        UNKNOWN_TYPE,
        UNSUPPORTED_VERSION,
        NBT_LIMIT
    }

    private final String typeId;
    private final Kind kind;
    private final String path;

    public ValidationException(String typeId, Kind kind, String path) {
        super("invalid " + (typeId == null ? "<none>" : typeId) + ": " + kind + " at "
                + (path == null || path.isEmpty() ? "$" : path));
        this.typeId = typeId;
        this.kind = kind;
        this.path = path == null ? "" : path;
    }

    /** Same violation, attributed to a message type (used when a lower layer did not know it). */
    public ValidationException withType(String type) {
        return new ValidationException(type, kind, path);
    }

    public String typeId() {
        return typeId;
    }

    public Kind kind() {
        return kind;
    }

    public String path() {
        return path;
    }
}
