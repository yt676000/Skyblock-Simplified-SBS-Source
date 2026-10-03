/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.trophyfish.logic;

import com.google.gson.JsonSyntaxException;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ProfileContext;
import sbs.modid.client.core.config.ProfileScopedStore;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.skills.trophyfish.model.TrophyTier;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Trophy fish counts per account and SkyBlock profile: the last Trophy Fishing menu read
 * (authoritative, replaces every fish it could read) plus catches counted from chat since.
 *
 * <p>Same profile-store rules as {@code MuseumStore}: nothing is latched as loaded while the profile
 * is unknown, nothing is written under the placeholder, and an unreadable or newer file is never
 * overwritten.
 */
public final class TrophyFishStore implements ProfileScopedStore {

    private static final TrophyFishStore INSTANCE = new TrophyFishStore();

    private static final String FILE = "trophy_fish.json";
    private static final int SCHEMA_VERSION = 1;
    private static final int TIERS = TrophyTier.values().length;

    /** The file, as Gson sees it. Keys are the fish's API key ({@code blobfish}). */
    static final class Data {
        int schemaVersion = SCHEMA_VERSION;
        /** When the menu was last read; 0 = never. */
        long syncedAt;
        Map<String, int[]> counts = new LinkedHashMap<>();
    }

    private Data data = new Data();
    private boolean loaded;
    private boolean unreadable;
    private volatile int generation;

    private TrophyFishStore() {
        ProfileContext.getInstance().register(this);
    }

    public static TrophyFishStore getInstance() {
        return INSTANCE;
    }

    public int generation() {
        return generation;
    }

    // ------------------------------------------------------------------ reads

    /** Whether the store can answer for this profile at all. */
    public synchronized boolean ready() {
        ensureLoaded();
        return loaded && ProfileContext.getInstance().known();
    }

    /** When the menu was last read for this profile; 0 = never. */
    public synchronized long syncedAt() {
        return ready() ? data.syncedAt : 0L;
    }

    /**
     * The count for one fish and tier: 0 not caught, {@code -1} caught with the count unknown
     * ({@link TrophyMenuParser#CAUGHT_UNKNOWN_COUNT}).
     */
    public synchronized int count(String apiKey, TrophyTier tier) {
        if (!ready()) {
            return 0;
        }
        int[] row = data.counts.get(apiKey);
        return row == null || row.length != TIERS ? 0 : row[tier.ordinal()];
    }

    // ------------------------------------------------------------------ writes

    /** A full menu read: every fish in {@code rows} is replaced; the others are left as they were. */
    public synchronized void recordMenu(Map<String, int[]> rows) {
        if (!ready() || rows.isEmpty()) {
            return;
        }
        for (Map.Entry<String, int[]> e : rows.entrySet()) {
            if (e.getValue() != null && e.getValue().length == TIERS) {
                data.counts.put(e.getKey(), e.getValue().clone());
            }
        }
        data.syncedAt = System.currentTimeMillis();
        generation++;
        save();
    }

    /**
     * One catch from chat, on top of the last sync.
     *
     * @return the count for that cell <b>before</b> the catch (so the caller can tell a new tier)
     */
    public synchronized int recordCatch(String apiKey, TrophyTier tier) {
        if (!ready()) {
            return 0;
        }
        int[] row = data.counts.computeIfAbsent(apiKey, k -> new int[TIERS]);
        int before = row[tier.ordinal()];
        if (before >= 0) {
            row[tier.ordinal()] = before + 1;   // an unknown count stays unknown, but stays caught
        }
        generation++;
        save();
        return before;
    }

    // ------------------------------------------------------------------ persistence

    private Path file() {
        return ProfileContext.getInstance().file(FILE);
    }

    private void ensureLoaded() {
        if (!loaded) {
            reloadProfile();
        }
    }

    @Override
    public synchronized void reloadProfile() {
        data = new Data();
        unreadable = false;
        loaded = false;
        generation++;
        if (!ProfileContext.getInstance().known()) {
            return;
        }
        Path path = file();
        if (!Files.isRegularFile(path)) {
            loaded = true;
            return;
        }
        try {
            Data read = SBSFiles.GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Data.class);
            if (read != null) {
                if (read.counts == null) {
                    read.counts = new LinkedHashMap<>();
                }
                read.counts.values().removeIf(row -> row == null || row.length != TIERS);
                if (read.schemaVersion > SCHEMA_VERSION) {
                    unreadable = true;
                }
                data = read;
            }
            loaded = true;
        } catch (JsonSyntaxException corrupt) {
            unreadable = true;
            loaded = true;
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Trophy] {} is unreadable ({}) - left as it is, "
                    + "counts will not be saved", FILE, corrupt.toString());
        } catch (Exception transientFailure) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Trophy] could not read {} yet ({})", FILE,
                    transientFailure.toString());
        }
    }

    @Override
    public synchronized void flushProfile() {
        if (loaded) {
            save();
        }
    }

    private void save() {
        if (!loaded || unreadable || !ProfileContext.getInstance().known()) {
            return;
        }
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            data.schemaVersion = SCHEMA_VERSION;
            Files.writeString(path, SBSFiles.GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Trophy] could not write {}: {}", FILE, e.toString());
        }
    }
}
