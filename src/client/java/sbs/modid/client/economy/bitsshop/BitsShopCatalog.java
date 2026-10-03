/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.bitsshop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.SBSFiles;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every item the Bits Shop sells, what it costs in bits, and which category page it was on.
 *
 * <p><b>Why this is learned rather than hardcoded.</b> Comparing bits-per-coin across the whole shop
 * needs the items in the <i>other</i> category pages, which are not open - so something has to
 * remember them. The obvious shape is a shipped table, and it is the wrong one: Hypixel restates
 * every bits price in the item's own tooltip, it changes them between updates, and a stale entry
 * here would not degrade gracefully - it would confidently rank the wrong item as the best buy,
 * which is worse than saying nothing. So the tooltip is treated as the authority and this file is
 * the memory of what it said. Open a category once and its items are known forever; open a category
 * whose prices Hypixel has since changed and the new numbers simply overwrite the old.
 *
 * <p>The {@link #SEED} below is a small day-one convenience, not a source of truth: every entry in
 * it is replaced the moment the real page is seen. It is deliberately tiny for the same reason - a
 * seeded price that is wrong is a wrong recommendation until the player happens to open that page.
 *
 * <p>Stored at {@code config/sbs/repo/bits_shop.json}, keyed by SkyBlock item id. Not profile-scoped:
 * the shop's catalogue is the same on every profile and every account.
 */
public final class BitsShopCatalog {

    private static final BitsShopCatalog INSTANCE = new BitsShopCatalog();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Entry>>() {
    }.getType();

    /** One shop offer: what you get, how much of it, what it costs, and where it was found. */
    public static final class Entry {
        public String id = "";
        public String name = "";
        public long bits;
        /** How many the offer hands over - a bulk offer is only comparable per bit, not per item. */
        public int amount = 1;
        /** Title of the category page it was seen on, so the tooltip can say where to buy it. */
        public String menu = "";
        /** Wall-clock of the last sighting; a seeded entry that was never seen keeps 0. */
        public long seenAt;

        Entry() {
        }

        Entry(String id, String name, long bits, int amount, String menu, long seenAt) {
            this.id = id;
            this.name = name;
            this.bits = bits;
            this.amount = amount;
            this.menu = menu;
            this.seenAt = seenAt;
        }

        /** Whether this came from the game rather than from {@link #SEED}. */
        public boolean observed() {
            return seenAt > 0;
        }
    }

    /**
     * Day-one entries, <b>unverified</b> and overwritten on first sight of the real page. Kept to
     * the few offers whose price is widely known and stable; anything less certain is left out on
     * purpose, because a wrong seed produces a wrong "best deal" until that page is opened.
     */
    private static final Map<String, long[]> SEED = new LinkedHashMap<>();

    static {
        // id -> {bits, amount}. Adding one is a single line; it self-corrects on first sight.
        SEED.put("KISMET_FEATHER", new long[]{1350, 1});
        SEED.put("GOD_POTION_2", new long[]{1000, 1});
        SEED.put("HEAT_CORE", new long[]{3000, 1});
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private volatile boolean loaded;

    private BitsShopCatalog() {
    }

    public static BitsShopCatalog getInstance() {
        return INSTANCE;
    }

    /** Every known offer, load-on-first-use. */
    public List<Entry> all() {
        ensureLoaded();
        return new ArrayList<>(entries.values());
    }

    public Entry get(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        ensureLoaded();
        return entries.get(id);
    }

    /** How many offers came from actually seeing them in game (what the settings page reports). */
    public int observedCount() {
        ensureLoaded();
        int count = 0;
        for (Entry entry : entries.values()) {
            if (entry.observed()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Records one offer seen in game. Writes to disk only when something actually changed, so
     * re-opening the same page every few seconds costs nothing.
     *
     * @return whether the catalogue changed
     */
    public boolean record(String id, String name, long bits, int amount, String menu) {
        if (id == null || id.isEmpty() || bits <= 0) {
            return false;
        }
        ensureLoaded();
        Entry existing = entries.get(id);
        if (existing != null && existing.observed() && existing.bits == bits
                && existing.amount == amount && menu.equals(existing.menu)) {
            return false;
        }
        entries.put(id, new Entry(id, name, bits, amount, menu, System.currentTimeMillis()));
        save();
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Bits] {} = {} bits x{} ({})", id, bits, amount, menu);
        return true;
    }

    /** Forgets everything learned, seeds included - the settings page's reset. */
    public void clear() {
        ensureLoaded();
        entries.clear();
        seed();
        save();
    }

    private synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        seed();
        Path path = SBSFiles.bitsShopFile();
        try {
            if (Files.exists(path)) {
                try (Reader reader = Files.newBufferedReader(path)) {
                    Map<String, Entry> stored = GSON.fromJson(reader, MAP_TYPE);
                    if (stored != null) {
                        // Stored entries win over seeds: they were observed, the seeds were guessed.
                        stored.forEach((key, entry) -> {
                            if (entry != null && entry.id != null && !entry.id.isEmpty()) {
                                entries.put(key, entry);
                            }
                        });
                    }
                }
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][Bits] Failed to load the bits shop catalogue", e);
        }
    }

    private void seed() {
        SEED.forEach((id, data) ->
                entries.put(id, new Entry(id, "", data[0], (int) data[1], "", 0L)));
    }

    private synchronized void save() {
        Path path = SBSFiles.bitsShopFile();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(entries, MAP_TYPE, writer);
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][Bits] Failed to save the bits shop catalogue", e);
        }
    }
}
