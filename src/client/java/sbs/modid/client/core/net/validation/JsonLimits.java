/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.net.validation;

/**
 * Structural limits applied while a JSON document is read, before any schema looks at it.
 *
 * @param maxBytes    largest accepted body, in bytes
 * @param maxDepth    deepest nesting of objects and arrays
 * @param maxArray    most elements in one array
 * @param maxMembers  most members in one object
 * @param maxString   longest string value or member name, in chars
 * @param maxElements most values in the whole document
 */
public record JsonLimits(long maxBytes, int maxDepth, int maxArray, int maxMembers, int maxString,
                         int maxElements) {

    public static final long MIB = 1024L * 1024L;

    /** SBS-defined messages: 1 MiB, depth 64. */
    public static final JsonLimits DEFAULT = new JsonLimits(MIB, 64, 10_000, 10_000, 32_768, 250_000);

    /** Relayed third-party documents (Hypixel, Mojang, item repo): larger, same depth. */
    public static final JsonLimits RELAY = new JsonLimits(32 * MIB, 64, 200_000, 200_000, 262_144, 4_000_000);

    public JsonLimits {
        if (maxBytes <= 0 || maxDepth <= 0 || maxArray <= 0 || maxMembers <= 0 || maxString <= 0
                || maxElements <= 0) {
            throw new IllegalArgumentException("limits must be positive");
        }
    }

    public JsonLimits withMaxBytes(long bytes) {
        return new JsonLimits(bytes, maxDepth, maxArray, maxMembers, maxString, maxElements);
    }
}
