/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.sound;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The list of sounds the Sound Manager can act on, and how they are grouped in its table.
 *
 * <p>Built from the game's own sound registry rather than a hand-kept list, so it is complete and
 * cannot drift: every sound the game knows about, including the ones a resource pack adds, is in
 * here. The registry is walked once and cached - it does not change while the game runs.
 */
public final class SoundCatalog {

    /** One row of the table. */
    public record Entry(Identifier id, String path, String category) {

        /** The short name shown in the table: the id without its namespace. */
        public String displayName() {
            return path;
        }
    }

    private static List<Entry> cached;

    private SoundCatalog() {
    }

    /** Every sound in the registry, sorted by id. Built once. */
    public static synchronized List<Entry> all() {
        if (cached != null) {
            return cached;
        }
        List<Entry> entries = new ArrayList<>(1024);
        for (Identifier id : BuiltInRegistries.SOUND_EVENT.keySet()) {
            entries.add(new Entry(id, id.getPath(), categoryOf(id.getPath())));
        }
        entries.sort((a, b) -> a.id().toString().compareTo(b.id().toString()));
        cached = List.copyOf(entries);
        return cached;
    }

    /**
     * The rows matching {@code query}, or everything when it is blank.
     *
     * <p>Called from the settings screen while typing, over ~1500 entries - a plain substring scan
     * is well inside a frame there, and this is deliberately NOT the code the sound engine calls
     * (that path is a hash lookup in {@code SoundControl}).
     */
    public static List<Entry> search(String query) {
        List<Entry> all = all();
        if (query == null || query.isBlank()) {
            return all;
        }
        String needle = query.trim().toLowerCase(Locale.ROOT);
        List<Entry> hits = new ArrayList<>();
        for (Entry entry : all) {
            if (entry.id().toString().contains(needle)) {
                hits.add(entry);
            }
        }
        return hits;
    }

    /**
     * A rough grouping for the table's category column, taken from the id's first segment
     * ({@code block.note_block.pling} -> "block"). The engine's real {@code SoundSource} is only
     * known per playing instance, not per registered sound, so this is what can be shown in a list.
     */
    private static String categoryOf(String path) {
        int dot = path.indexOf('.');
        return dot <= 0 ? "other" : path.substring(0, dot);
    }
}
