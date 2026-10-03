/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.model;

import sbs.modid.client.core.build.model.StateStrings;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * "All oak becomes spruce": which block ids of a build a swap renames.
 *
 * <p>Two forms. A whole id ({@code oak_planks -> spruce_planks}) swaps that block alone. A word
 * ({@code oak -> spruce}) swaps it as a whole underscore-separated part of every id - so
 * {@code oak_stairs}, {@code oak_log} and {@code stripped_oak_wood} all follow. {@code dark_oak_planks}
 * would become {@code dark_spruce_planks}, which is not a block, so it stays: a result that is not a
 * real block is dropped, and a swap never turns part of a build into air.
 *
 * <p>Pure: whether an id exists is asked of a predicate (the game's registry in play, a set in tests).
 */
public final class PaletteSwap {

    private PaletteSwap() {
    }

    /**
     * The renames {@code from -> to} makes among {@code blockIds}.
     *
     * @param exists whether a namespaced block id is a real block
     */
    public static Map<String, String> plan(Collection<String> blockIds, String from, String to, Predicate<String> exists) {
        Map<String, String> out = new LinkedHashMap<>();
        String fromId = StateStrings.withNamespace(from);
        String toId = StateStrings.withNamespace(to);
        if (blockIds.contains(fromId) && exists.test(toId)) {
            out.put(fromId, toId);
            return out;
        }
        String word = from.trim().toLowerCase(java.util.Locale.ROOT);
        String replacement = to.trim().toLowerCase(java.util.Locale.ROOT);
        if (word.isEmpty() || word.contains(":")) {
            return out;
        }
        for (String id : blockIds) {
            int colon = id.indexOf(':');
            String namespace = id.substring(0, colon + 1);
            String[] parts = id.substring(colon + 1).split("_");
            String wordFirst = word.split("_")[0];
            String[] wordParts = word.split("_");
            boolean changed = false;
            StringBuilder path = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                boolean matches = parts[i].equals(wordFirst) && i + wordParts.length <= parts.length;
                for (int k = 1; matches && k < wordParts.length; k++) {
                    matches = parts[i + k].equals(wordParts[k]);
                }
                if (path.length() > 0) {
                    path.append('_');
                }
                if (matches) {
                    path.append(replacement);
                    i += wordParts.length - 1;
                    changed = true;
                } else {
                    path.append(parts[i]);
                }
            }
            String swapped = namespace + path;
            if (changed && !swapped.equals(id) && exists.test(swapped)) {
                out.put(id, swapped);
            }
        }
        return out;
    }

    /** Adds {@code next} on top of {@code current}: earlier targets follow the new swap too. */
    public static Map<String, String> chain(Map<String, String> current, Map<String, String> next) {
        Map<String, String> out = new LinkedHashMap<>();
        current.forEach((key, value) -> out.put(key, next.getOrDefault(value, value)));
        next.forEach(out::putIfAbsent);
        out.entrySet().removeIf(entry -> entry.getKey().equals(entry.getValue()));
        return out;
    }
}
