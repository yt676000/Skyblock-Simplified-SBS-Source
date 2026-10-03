/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.minions.logic;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ProfileContext;
import sbs.modid.client.core.config.ProfileScopedStore;
import sbs.modid.client.core.config.SBSFiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the client has witnessed about THIS account+profile's minions: the crafted-tier check marks
 * and slot limit from the Crafted Minions menu, the placed minions seen by the island armor-stand
 * scan, and the fuel/upgrade configurations read from opened minion GUIs.
 *
 * <p>Everything here is <b>witnessed, never assumed</b> - the profile API's
 * {@code crafted_generators} would say the same things, but the server's key-gated proxy is not a
 * dependency this needs when the menus and the island itself carry the facts. It necessarily
 * starts empty and fills in as the player opens the menus once.
 *
 * <p><b>Merge rules match what can actually change.</b> Crafted tiers only ever grow, so pages
 * merge in. Placed minions can be picked up, so each island visit starts a fresh baseline: the
 * first scan of a visit replaces the set, later scans of the same visit only add (the player
 * walking around reveals more of the island, never less).
 *
 * <p>Per account and per SkyBlock profile via {@link ProfileScopedStore}, like every other
 * witnessed-progress store.
 */
public final class MinionStateStore implements ProfileScopedStore {

    private static final MinionStateStore INSTANCE = new MinionStateStore();

    private static final String FILE = "minions.json";

    /** One group of identical placed minions, as the stand scan saw them. */
    public static final class Placed {
        public String type = "";
        public int tier;
        public int count;

        /** Gson needs a no-arg constructor. */
        public Placed() {
        }

        Placed(String type, int tier, int count) {
            this.type = type;
            this.tier = tier;
            this.count = count;
        }
    }

    /**
     * One minion the last scan found stopped, with where it stands and what it said.
     *
     * <p>Keyed on the packed block position rather than on type and tier: several identical
     * minions sit side by side, and "which one" is the entire point of the world marker.
     */
    public static final class Stopped {
        /** Packed block position of the minion's own stand - the identity of this entry. */
        public long pos;
        /** Feet position, for the box. */
        public double x;
        public double y;
        public double z;
        /** Generator type ("SNOW"), or empty when the skin matched no catalog entry. */
        public String type = "";
        public int tier;
        public sbs.modid.client.economy.minions.model.MinionStopReason reason =
                sbs.modid.client.economy.minions.model.MinionStopReason.OTHER;
        /** The hologram text as read, colour codes stripped. Kept so the log and the UI agree. */
        public String text = "";
        public long seenAt;

        /** Gson needs a no-arg constructor. */
        public Stopped() {
        }
    }

    /** One minion GUI's captured configuration (the parts a stand scan cannot see). */
    public static final class Config {
        public String type = "";
        public int tier;
        public String fuelId = "";
        public String hopperId = "";
        public List<String> upgradeIds = new ArrayList<>();
        public long seenAt;

        /** Gson needs a no-arg constructor. */
        public Config() {
        }
    }

    /** The on-disk shape. A class rather than a record so Gson can build it field by field. */
    private static final class Persisted {
        /** "SNOW_5"-style crafted unique tiers, merged across Crafted Minions pages. */
        Set<String> craftedTiers = new LinkedHashSet<>();
        long craftedReadAt;

        /** "Minions limit: N" as the Crafted Minions menu stated it; 0 = never read. */
        int minionsLimit;

        List<Placed> placed = new ArrayList<>();
        /** Small head-wearing stands whose skin matched no catalog tier (minion skins, mostly). */
        int unknownPlaced;
        long scanAt;

        List<Config> configs = new ArrayList<>();

        /** Minions the scan last found stopped. Emptied on a fresh visit, see recordStopped. */
        List<Stopped> stopped = new ArrayList<>();
        long stoppedAt;
    }

    private volatile Persisted state = new Persisted();
    private volatile boolean loaded;

    /** Bumped when the player (re)enters the Private Island; scans compare against it. */
    private volatile int visitId;
    private volatile int lastScanVisit = -1;
    private volatile int lastStoppedVisit = -1;

    private MinionStateStore() {
        ProfileContext.getInstance().register(this);
    }

    public static MinionStateStore getInstance() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------ queries

    /** The crafted unique tiers on record ("SNOW_5"), merged from every page seen. */
    public Set<String> craftedTiers() {
        ensureLoaded();
        return Set.copyOf(state.craftedTiers);
    }

    /** How many unique tiers are on record - the number the slot table runs on. */
    public int uniqueCraftCount() {
        ensureLoaded();
        return state.craftedTiers.size();
    }

    /** When the Crafted Minions menu was last read; {@code 0} = never (records incomplete). */
    public long craftedReadAt() {
        ensureLoaded();
        return state.craftedReadAt;
    }

    /** The slot limit the Crafted Minions menu stated, or {@code 0} when it was never read. */
    public int minionsLimit() {
        ensureLoaded();
        return state.minionsLimit;
    }

    /** Every placed group the last island scan(s) saw. */
    public List<Placed> placed() {
        ensureLoaded();
        return List.copyOf(state.placed);
    }

    /** Placed count for one minion type across tiers. */
    public int placedCount(String type) {
        ensureLoaded();
        int count = 0;
        for (Placed group : state.placed) {
            if (group.type.equals(type)) {
                count += group.count;
            }
        }
        return count;
    }

    /** The highest placed tier of a type, or {@code 0} when none is on record. */
    public int highestPlacedTier(String type) {
        ensureLoaded();
        int highest = 0;
        for (Placed group : state.placed) {
            if (group.type.equals(type)) {
                highest = Math.max(highest, group.tier);
            }
        }
        return highest;
    }

    /** Total placed minions on record (matched stands only). */
    public int placedTotal() {
        ensureLoaded();
        int total = 0;
        for (Placed group : state.placed) {
            total += group.count;
        }
        return total;
    }

    /** Stands that looked like minions but matched no catalog skin (cosmetic skins, mostly). */
    public int unknownPlaced() {
        ensureLoaded();
        return state.unknownPlaced;
    }

    /** When the island was last scanned; {@code 0} = never. */
    public long scanAt() {
        ensureLoaded();
        return state.scanAt;
    }

    /** The freshest captured GUI configuration for a type (any tier), or {@code null}. */
    public Config configFor(String type) {
        ensureLoaded();
        Config best = null;
        for (Config config : state.configs) {
            if (config.type.equals(type) && (best == null || config.seenAt > best.seenAt)) {
                best = config;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ mutations

    /** Merges crafted tiers read from one Crafted Minions page (check marks only ever appear). */
    public void recordCrafted(Set<String> tiers) {
        ensureLoaded();
        boolean changed = false;
        if (tiers != null && state.craftedTiers.addAll(tiers)) {
            changed = true;
        }
        long now = System.currentTimeMillis();
        if (now - state.craftedReadAt > 1000) {
            state.craftedReadAt = now;
            changed = true;
        }
        if (changed) {
            save();
        }
    }

    /** Records the menu's own "Minions limit" line - the authoritative slot count. */
    public void recordMinionsLimit(int limit) {
        ensureLoaded();
        if (limit > 0 && limit != state.minionsLimit) {
            state.minionsLimit = limit;
            save();
        }
    }

    /** Marks a fresh Private Island visit: the next scan becomes the new baseline. */
    public void beginVisit() {
        visitId++;
    }

    /**
     * Records one stand scan. The first scan of a visit replaces the placed set (a picked-up
     * minion disappears here and nowhere else); later scans of the same visit only merge upward,
     * because walking around reveals stands, never removes them.
     */
    public void recordScan(Map<String, Map<Integer, Integer>> counts, int unknown) {
        ensureLoaded();
        List<Placed> scanned = new ArrayList<>();
        for (var typeEntry : counts.entrySet()) {
            for (var tierEntry : typeEntry.getValue().entrySet()) {
                scanned.add(new Placed(typeEntry.getKey(), tierEntry.getKey(), tierEntry.getValue()));
            }
        }
        if (lastScanVisit != visitId) {
            state.placed = scanned;
            state.unknownPlaced = unknown;
            lastScanVisit = visitId;
        } else {
            for (Placed seen : scanned) {
                Placed existing = null;
                for (Placed group : state.placed) {
                    if (group.type.equals(seen.type) && group.tier == seen.tier) {
                        existing = group;
                        break;
                    }
                }
                if (existing == null) {
                    state.placed.add(seen);
                } else {
                    existing.count = Math.max(existing.count, seen.count);
                }
            }
            state.unknownPlaced = Math.max(state.unknownPlaced, unknown);
        }
        state.scanAt = System.currentTimeMillis();
        save();
    }

    /**
     * Records which minions the scan just looked at, and which of those were stopped.
     *
     * <p>Unlike {@link #recordScan}, this does <b>not</b> merge upward: a minion the scan saw and
     * found working must lose its flag, or emptying one while you stand next to it would leave it
     * marked until you left the island and came back. Minions <i>not</i> in {@code seen} are out of
     * range rather than fixed, so their entries are left alone.
     *
     * <p>A fresh visit clears the lot - the island is re-read from scratch and a minion picked up
     * since must not survive as a ghost marker.
     *
     * @param seen    packed block positions of every minion stand this scan looked at
     * @param stopped the subset that was stopped, with their reasons
     */
    public void recordStopped(Set<Long> seen, List<Stopped> stopped) {
        ensureLoaded();
        if (lastStoppedVisit != visitId) {
            state.stopped.clear();
            lastStoppedVisit = visitId;
        }
        state.stopped.removeIf(entry -> seen.contains(entry.pos));
        state.stopped.addAll(stopped);
        state.stoppedAt = System.currentTimeMillis();
        save();
    }

    /** The minions on record as stopped, freshest scan first-hand. Never null. */
    public List<Stopped> stopped() {
        ensureLoaded();
        return state.stopped;
    }

    /** How many are stopped for the given reason. */
    public int stoppedCount(sbs.modid.client.economy.minions.model.MinionStopReason reason) {
        ensureLoaded();
        int count = 0;
        for (Stopped entry : state.stopped) {
            if (entry.reason == reason) {
                count++;
            }
        }
        return count;
    }

    /** When the stopped set was last written, or 0 when never. Every label showing it says its age. */
    public long stoppedAt() {
        ensureLoaded();
        return state.stoppedAt;
    }

    /** Records one opened minion GUI's configuration, replacing the previous capture of its type+tier. */
    public void recordConfig(Config config) {
        ensureLoaded();
        state.configs.removeIf(existing ->
                existing.type.equals(config.type) && existing.tier == config.tier);
        config.seenAt = System.currentTimeMillis();
        state.configs.add(config);
        save();
    }

    /** Clears everything witnessed for the current profile. */
    public void reset() {
        ensureLoaded();
        state = new Persisted();
        save();
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
        Persisted read = new Persisted();
        try {
            Path path = file();
            if (Files.isRegularFile(path)) {
                try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    Persisted parsed = SBSFiles.GSON.fromJson(reader, Persisted.class);
                    if (parsed != null) {
                        read.craftedTiers = parsed.craftedTiers == null
                                ? new LinkedHashSet<>() : parsed.craftedTiers;
                        read.craftedReadAt = parsed.craftedReadAt;
                        read.minionsLimit = parsed.minionsLimit;
                        read.placed = parsed.placed == null ? new ArrayList<>() : parsed.placed;
                        read.unknownPlaced = parsed.unknownPlaced;
                        read.scanAt = parsed.scanAt;
                        read.configs = parsed.configs == null ? new ArrayList<>() : parsed.configs;
                    }
                }
            }
        } catch (Exception e) {
            // A corrupt file must not wipe witnessed progress silently - keep it on disk and start
            // empty in memory so the next save does not overwrite what might be recoverable.
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Minions] could not read {} ({})", FILE, e.toString());
        }
        state = read;
        loaded = true;
        lastScanVisit = -1;
    }

    @Override
    public void flushProfile() {
        if (loaded) {
            save();
        }
    }

    private synchronized void save() {
        // "default" is the placeholder before the profile context knows where it is; writing there
        // would file this profile's records under a name that belongs to nobody.
        if (ProfileContext.getInstance().profile().equals("default")) {
            return;
        }
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, SBSFiles.GSON.toJson(state), StandardCharsets.UTF_8);
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Minions] could not write {}", FILE, e);
        }
    }
}
