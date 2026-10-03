/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.build.model;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Pure string handling of block-state syntax: {@code namespace:id[key=value,key=value]}.
 *
 * <p>Enough to split a state into its block id and properties and put one back together - what the
 * vanilla structure format (which stores {@code Name} and {@code Properties} apart), palette swaps and
 * material lists need. Validity against the game's registry is not this class's question; that is
 * {@code logic/BlockStates}.
 */
public final class StateStrings {

    private StateStrings() {
    }

    /** The block id without properties: {@code minecraft:stone} from {@code minecraft:stone[x=y]}. */
    public static String blockId(String state) {
        int open = state.indexOf('[');
        return open < 0 ? state : state.substring(0, open);
    }

    /** The properties, in their written order; empty for a state without brackets. */
    public static Map<String, String> properties(String state) {
        Map<String, String> out = new LinkedHashMap<>();
        int open = state.indexOf('[');
        if (open < 0 || !state.endsWith("]")) {
            return out;
        }
        String body = state.substring(open + 1, state.length() - 1);
        if (body.isEmpty()) {
            return out;
        }
        for (String pair : body.split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
        return out;
    }

    /** Puts an id and properties back together; properties sorted by key, as the game prints them. */
    public static String compose(String blockId, Map<String, String> properties) {
        if (properties == null || properties.isEmpty()) {
            return blockId;
        }
        StringBuilder out = new StringBuilder(blockId).append('[');
        boolean first = true;
        for (Map.Entry<String, String> entry : new java.util.TreeMap<>(properties).entrySet()) {
            if (!first) {
                out.append(',');
            }
            out.append(entry.getKey()).append('=').append(entry.getValue());
            first = false;
        }
        return out.append(']').toString();
    }

    /** {@code minecraft:stone} for {@code stone}; an id that already has a namespace is kept. */
    public static String withNamespace(String id) {
        String trimmed = id.trim().toLowerCase(Locale.ROOT);
        return trimmed.indexOf(':') >= 0 ? trimmed : "minecraft:" + trimmed;
    }

    /** {@code oak planks} from {@code minecraft:oak_planks} - how a block reads in a list. */
    public static String displayName(String state) {
        String id = blockId(state);
        int colon = id.indexOf(':');
        String path = colon >= 0 ? id.substring(colon + 1) : id;
        return path.replace('_', ' ');
    }
}
