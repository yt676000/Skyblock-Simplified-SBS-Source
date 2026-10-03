/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.npcshop.logic;

import com.google.gson.reflect.TypeToken;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.economy.npcshop.model.ShopCost;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What NPC shops charge, learned from their tooltips and kept account-wide in
 * {@link SBSFiles#npcShopsFile()} - a shop's prices do not depend on the profile. Never hardcoded:
 * every entry is something a shop menu actually showed, with the date it showed it.
 *
 * <p>Written once a change has settled (750 ms), atomically; a file that cannot be read is moved
 * aside rather than overwritten, so a hand edit gone wrong costs nothing that was learned.
 */
public final class NpcShopCatalog {

    private static final NpcShopCatalog INSTANCE = new NpcShopCatalog();

    private static final int SCHEMA_VERSION = 1;
    private static final long WRITE_DELAY_MS = 750L;

    /** One offer as a shop showed it. Field names are the file format. */
    public static final class Entry {
        public String npc;
        public String itemId;
        public String name;
        /** Coin price for {@link #stackSize} units; {@code null} when the price has no coins. */
        public Long coins;
        public Long bits;
        public List<ShopCost.ItemCost> items = new ArrayList<>();
        public int stackSize = 1;
        public String limitLine;
        public long learnedAt;

        public ShopCost.Cost cost() {
            return new ShopCost.Cost(coins, bits, items == null ? List.of() : items, limitLine);
        }
    }

    private static final class Data {
        int schemaVersion = SCHEMA_VERSION;
        List<Entry> offers = new ArrayList<>();
    }

    private final Map<String, Entry> offers = new LinkedHashMap<>();
    private boolean loaded;
    private boolean readOnly;
    private boolean dirty;
    private long dirtyAt;
    private int generation;

    private NpcShopCatalog() {
    }

    public static NpcShopCatalog getInstance() {
        return INSTANCE;
    }

    private static String key(String npc, String itemId) {
        return npc.toLowerCase(Locale.ROOT) + "|" + itemId.toUpperCase(Locale.ROOT);
    }

    public synchronized int generation() {
        ensureLoaded();
        return generation;
    }

    public synchronized List<Entry> all() {
        ensureLoaded();
        return List.copyOf(offers.values());
    }

    public synchronized Entry get(String npc, String itemId) {
        ensureLoaded();
        return npc == null || itemId == null ? null : offers.get(key(npc, itemId));
    }

    /** How many distinct shops have been learned. */
    public synchronized int shopCount() {
        ensureLoaded();
        Set<String> npcs = new HashSet<>();
        for (Entry entry : offers.values()) {
            npcs.add(entry.npc.toLowerCase(Locale.ROOT));
        }
        return npcs.size();
    }

    /** Records one offer; only an actual change marks the file for writing. */
    public synchronized void record(String npc, String itemId, String name, ShopCost.Cost cost, int stackSize) {
        ensureLoaded();
        String key = key(npc, itemId);
        Entry old = offers.get(key);
        if (old != null && java.util.Objects.equals(old.coins, cost.coins())
                && java.util.Objects.equals(old.bits, cost.bits()) && old.stackSize == stackSize
                && java.util.Objects.equals(old.items, cost.items())
                && java.util.Objects.equals(old.limitLine, cost.limitLine())) {
            return;
        }
        Entry entry = new Entry();
        entry.npc = npc;
        entry.itemId = itemId.toUpperCase(Locale.ROOT);
        entry.name = name;
        entry.coins = cost.coins();
        entry.bits = cost.bits();
        entry.items = new ArrayList<>(cost.items());
        entry.stackSize = Math.max(1, stackSize);
        entry.limitLine = cost.limitLine();
        entry.learnedAt = System.currentTimeMillis();
        offers.put(key, entry);
        dirty = true;
        dirtyAt = System.currentTimeMillis();
        generation++;
    }

    // ------------------------------------------------------------------ file

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path path = SBSFiles.npcShopsFile();
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            Data data = SBSFiles.GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8),
                    new TypeToken<Data>() { }.getType());
            if (data != null && data.offers != null) {
                if (data.schemaVersion > SCHEMA_VERSION) {
                    readOnly = true;
                }
                for (Entry entry : data.offers) {
                    if (entry != null && entry.npc != null && entry.itemId != null) {
                        if (entry.items == null) {
                            entry.items = new ArrayList<>();
                        }
                        offers.put(key(entry.npc, entry.itemId), entry);
                    }
                }
            }
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][NpcShop] loaded {} offer(s) from {} shop(s)",
                    offers.size(), shopCountUnlocked());
        } catch (Exception e) {
            Path aside = path.resolveSibling(path.getFileName() + ".corrupt-" + System.currentTimeMillis());
            try {
                Files.move(path, aside, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception moveFailed) {
                readOnly = true;
            }
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][NpcShop] could not read {} ({}) - starting empty",
                    path.getFileName(), e.toString());
        }
    }

    private int shopCountUnlocked() {
        Set<String> npcs = new HashSet<>();
        offers.values().forEach(entry -> npcs.add(entry.npc.toLowerCase(Locale.ROOT)));
        return npcs.size();
    }

    /** Game tick: writes a settled change. */
    public synchronized void tick() {
        if (!dirty || readOnly || System.currentTimeMillis() - dirtyAt < WRITE_DELAY_MS) {
            return;
        }
        dirty = false;
        try {
            Path path = SBSFiles.npcShopsFile();
            SBSFiles.ensureParent(path);
            Data data = new Data();
            data.offers = new ArrayList<>(offers.values());
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temp, SBSFiles.GSON.toJson(data), StandardCharsets.UTF_8);
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            dirty = true;
            dirtyAt = System.currentTimeMillis();
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][NpcShop] could not write the catalogue: {}", e.toString());
        }
    }
}
