/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.tracker;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.SBSFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistent, human-readable <b>all-time</b> drop logs, one file per tracker under
 * {@code config/sbs/tracker/} (e.g. {@code fishingtracker.txt}). Every drop a tracker books is added
 * to a running total that survives restarts, so the file is a lifetime record of what a tracker has
 * seen – independent of the per-session HUD counters.
 *
 * <p>Each tracker keeps one in-memory tally, seeded from its file on first use and written back on a
 * throttle (and on world change via {@link #flushAll()}). The file lists {@code Name: count} lines,
 * most-collected first, so it reads at a glance.
 */
public final class TrackerStore {

    private static final long SAVE_THROTTLE_MS = 5_000L;

    /** One tracker's lifetime tally + write state. */
    private static final class Store {
        final String name;
        final Map<String, Long> totals = new LinkedHashMap<>();
        boolean loaded;
        boolean dirty;
        long lastSaveAt;

        Store(String name) {
            this.name = name;
        }
    }

    private static final Map<String, Store> STORES = new ConcurrentHashMap<>();

    private TrackerStore() {
    }

    /**
     * Adds {@code count} of {@code itemId} to {@code trackerName}'s lifetime total and schedules a
     * throttled write. The id is prettified for the file ({@code WITHER_GOGGLES → "Wither Goggles"}).
     */
    public static void record(String trackerName, String itemId, long count) {
        if (trackerName == null || itemId == null || itemId.isEmpty() || count <= 0) {
            return;
        }
        Store store = STORES.computeIfAbsent(trackerName, Store::new);
        synchronized (store) {
            load(store);
            store.totals.merge(prettify(itemId), count, Long::sum);
            store.dirty = true;
            long now = System.currentTimeMillis();
            if (now - store.lastSaveAt >= SAVE_THROTTLE_MS) {
                write(store);
            }
        }
    }

    /** Writes every dirty tracker now (call on world leave / shutdown). */
    public static void flushAll() {
        for (Store store : STORES.values()) {
            synchronized (store) {
                if (store.dirty) {
                    write(store);
                }
            }
        }
    }

    // ------------------------------------------------------------------ IO

    private static void load(Store store) {
        if (store.loaded) {
            return;
        }
        store.loaded = true;
        Path file = SBSFiles.trackerFile(store.name);
        try {
            if (!Files.exists(file)) {
                return;
            }
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                int colon = trimmed.lastIndexOf(": ");
                if (trimmed.startsWith("#") || colon <= 0) {
                    continue;
                }
                try {
                    long value = Long.parseLong(trimmed.substring(colon + 2).replaceAll("[^0-9]", ""));
                    store.totals.merge(trimmed.substring(0, colon), value, Long::sum);
                } catch (NumberFormatException ignored) {
                    // A hand-edited or malformed line – skip it, keep the rest.
                }
            }
        } catch (IOException e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Tracker] failed to read {}", file, e);
        }
    }

    private static void write(Store store) {
        store.dirty = false;
        store.lastSaveAt = System.currentTimeMillis();
        Path file = SBSFiles.trackerFile(store.name);
        List<Map.Entry<String, Long>> sorted = new ArrayList<>(store.totals.entrySet());
        sorted.sort(Map.Entry.<String, Long>comparingByValue().reversed());
        StringBuilder out = new StringBuilder();
        out.append("# ").append(store.name).append(" — all-time drops (auto-generated)\n");
        long total = 0;
        for (Map.Entry<String, Long> entry : sorted) {
            out.append(entry.getKey()).append(": ").append(String.format(Locale.US, "%,d", entry.getValue()))
                    .append('\n');
            total += entry.getValue();
        }
        out.append("# total items: ").append(String.format(Locale.US, "%,d", total)).append('\n');
        try {
            SBSFiles.ensureParent(file);
            Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Tracker] failed to write {}", file, e);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** {@code SHINY_FISH_SHARD → "Shiny Fish Shard"}; {@code SHARD_X → "X Shard"}. */
    private static String prettify(String itemId) {
        String id = itemId;
        boolean shard = id.startsWith("SHARD_");
        if (shard) {
            id = id.substring("SHARD_".length());
        }
        String[] words = id.toLowerCase(Locale.ROOT).split("_");
        StringBuilder out = new StringBuilder(id.length());
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        if (shard) {
            out.append(" Shard");
        }
        return out.toString();
    }
}
