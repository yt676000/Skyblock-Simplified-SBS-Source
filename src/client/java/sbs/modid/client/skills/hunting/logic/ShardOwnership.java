/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.hunting.logic;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ProfileContext;
import sbs.modid.client.core.config.ProfileScopedStore;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.skills.hunting.model.ShardId;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What this profile owns, keyed by {@code ATTRIBUTE_SHARD_<NAME>} and kept across sessions.
 *
 * <p><b>This is the "owned storage" the missing list is the complement of.</b> Which shards exist is
 * static data ({@link ShardCatalog}); which of them the player has is learned by being shown them,
 * because the menus are server-side and there is no request that returns "your shards". So every
 * reading is written here and kept - a shard read last week still counts as owned today, and paging
 * through the menu once is enough rather than once per session.
 *
 * <p><b>Two sources are stored side by side rather than merged into one number</b>, because they
 * answer different questions and one of them can legitimately fall:
 *
 * <ul>
 *   <li><b>The Hunting Box</b> states {@code "Owned: 1,234 Shards"} - exactly how many are being
 *       held right now. That number goes <i>down</i> when the player syphons or fuses, so the newest
 *       box reading always replaces the previous one. Taking a maximum here would leave a spent
 *       stack on screen for ever.</li>
 *   <li><b>The Attribute Menu</b> yields how many have already gone into an attribute, derived from
 *       its tier and its "Syphon N shards…" line. Those are spent, not held, so they are not the
 *       same shards the box is counting and adding the two would double-count nothing useful.</li>
 * </ul>
 *
 * <p><b>The tier only ever goes up.</b> An attribute cannot be un-levelled, so a lower reading is a
 * worse reading rather than news - the same argument that made merging the right direction in the
 * index this class replaces. {@link #clear()} is the escape hatch for a record that is wrong for
 * some other reason, and it is what the "Forget Read Shards" button calls.
 *
 * <p>Profile-scoped: this belongs to one Minecraft account plus one SkyBlock profile, and reading
 * another profile's into this one would be a correctness bug rather than a cosmetic one. Registered
 * from this class's own constructor, never from the client initializer - registering early forces
 * classes to load before the item registry is bound.
 */
public final class ShardOwnership implements ProfileScopedStore {

    private static final ShardOwnership INSTANCE = new ShardOwnership();

    /** Snapshot file name inside the profile directory. */
    private static final String FILE = "shard_ownership.json";

    /** How long after a burst of readings the file is written. Scans land in bursts while paging. */

    /** One shard's record. A plain class so Gson can rebuild it field by field. */
    public static final class Owned {

        /** Display name as last read, so the panel can name a shard the catalogue has not got. */
        public String name = "";

        /** Shards held in the Hunting Box at the last box reading, or {@code -1} when never read. */
        public int inBox = -1;

        /** Shards already syphoned into the attribute, or {@code -1} when not derivable. */
        public int syphoned = -1;

        /** The attribute's tier as last read, {@code 0} when not started, {@code -1} when unread. */
        public int tier = -1;

        /** When the Hunting Box last stated this shard. */
        public long boxAt;

        /** When the Attribute Menu last stated it. */
        public long menuAt;

        public Owned() {
        }

        /** Whether anything at all says the player has met this shard. */
        public boolean owned() {
            return inBox > 0 || syphoned > 0 || tier > 0;
        }

        /**
         * The amount to show. The box's figure where there is one, because it is stated outright
         * rather than derived; otherwise what the menu implies has gone into the attribute.
         */
        public int amount() {
            return inBox >= 0 ? inBox : syphoned;
        }

        /** When either source last stated this shard. */
        public long seenAt() {
            return Math.max(boxAt, menuAt);
        }
    }

    /** What Gson writes: the records, and nothing else. No item stacks, no page bookkeeping. */
    private static final class Snapshot {
        Map<String, Owned> owned = new LinkedHashMap<>();
    }

    private volatile Snapshot snapshot = new Snapshot();

    private boolean loaded;
    /** A save inside the interval is deferred to {@link #tick}, never dropped. */
    private final sbs.modid.client.core.config.SaveThrottle saveThrottle =
            new sbs.modid.client.core.config.SaveThrottle();

    private ShardOwnership() {
        ProfileContext.getInstance().register(this);
    }

    public static ShardOwnership getInstance() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------
    // Capture
    // ------------------------------------------------------------------

    /**
     * Records a Hunting Box reading: how many of this shard are being held right now.
     *
     * <p>Replaces rather than accumulates - see the class comment. A negative {@code amount} means
     * the box was read but did not state one, and leaves the previous figure alone.
     */
    public synchronized void noteBox(String canonicalId, String name, int amount) {
        Owned record = record(canonicalId, name);
        if (record == null || amount < 0) {
            return;
        }
        record.inBox = amount;
        record.boxAt = System.currentTimeMillis();
        save();
    }

    /**
     * Records an Attribute Menu reading: the attribute's tier, and how many shards have gone into it.
     *
     * @param tier     the tier read off the entry's name, or {@code -1} when it stated none
     * @param syphoned what {@code ShardLevelling} derived, or {@link ShardLevelling#UNKNOWN}
     */
    public synchronized void noteMenu(String canonicalId, String name, int tier, int syphoned) {
        Owned record = record(canonicalId, name);
        if (record == null) {
            return;
        }
        if (tier >= 0) {
            record.tier = Math.max(record.tier, tier);   // an attribute cannot be un-levelled
        }
        if (syphoned >= 0) {
            record.syphoned = syphoned;
        }
        record.menuAt = System.currentTimeMillis();
        save();
    }

    /** Clears every box figure, so a box read as empty does not leave yesterday's stacks behind. */
    public synchronized void forgetBoxAmounts() {
        ensureLoaded();
        for (Owned record : snapshot.owned.values()) {
            record.inBox = -1;
        }
    }

    private Owned record(String canonicalId, String name) {
        ensureLoaded();
        String key = ShardId.key(canonicalId);
        if (key == null) {
            return null;
        }
        Owned record = snapshot.owned.computeIfAbsent(key, ignored -> new Owned());
        if (name != null && !name.isBlank()) {
            record.name = name;
        }
        return record;
    }

    // ------------------------------------------------------------------
    // Query
    // ------------------------------------------------------------------

    /** Every record this profile has, keyed by {@code ATTRIBUTE_SHARD_<NAME>}. */
    public synchronized Map<String, Owned> all() {
        ensureLoaded();
        return Map.copyOf(snapshot.owned);
    }

    /** One shard's record, or {@code null} when nothing has ever stated it. */
    public synchronized Owned get(String canonicalId) {
        ensureLoaded();
        String key = ShardId.key(canonicalId);
        return key == null ? null : snapshot.owned.get(key);
    }


    /** How many shards have been met at least once - the numerator of the missing count. */
    public synchronized int ownedCount() {
        ensureLoaded();
        int count = 0;
        for (Owned record : snapshot.owned.values()) {
            if (record.owned()) {
                count++;
            }
        }
        return count;
    }


    /** When anything was last read, or {@code 0} when nothing ever was. */
    public synchronized long lastSeen() {
        ensureLoaded();
        long latest = 0;
        for (Owned record : snapshot.owned.values()) {
            latest = Math.max(latest, record.seenAt());
        }
        return latest;
    }


    /** Forgets everything. The manual reset for a record that no longer matches the game. */
    public synchronized void clear() {
        ensureLoaded();
        snapshot = new Snapshot();
        persist();
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        snapshot = read();
    }

    private static Snapshot read() {
        Path path = SBSFiles.profileFile(FILE);
        try {
            if (!java.nio.file.Files.exists(path)) {
                return new Snapshot();
            }
            try (var reader = java.nio.file.Files.newBufferedReader(path)) {
                Snapshot stored = SBSFiles.GSON.fromJson(reader, Snapshot.class);
                if (stored == null || stored.owned == null) {
                    return new Snapshot();
                }
                stored.owned.values().removeIf(java.util.Objects::isNull);
                return stored;
            }
        } catch (Throwable corrupt) {
            // Expected failure mode for a cache: log at info and carry on with nothing recorded.
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Shards] could not read {}: {}", path,
                    corrupt.toString());
            return new Snapshot();
        }
    }

    /** Writes at most once every few seconds; readings land in bursts while a menu is paged. */
    private void save() {
        long now = System.currentTimeMillis();
        if (saveThrottle.request(false, now)) {
            saveThrottle.written(now);
            persist();
        }
    }

    /** Client tick: writes a change the throttle deferred, once its interval has passed. */
    public synchronized void tick() {
        long now = System.currentTimeMillis();
        if (saveThrottle.pending(now)) {
            saveThrottle.written(now);
            persist();
        }
    }

    private void persist() {
        Path path = SBSFiles.profileFile(FILE);
        try {
            SBSFiles.ensureParent(path);
            try (var writer = java.nio.file.Files.newBufferedWriter(path)) {
                SBSFiles.GSON.toJson(snapshot, writer);
            }
        } catch (Throwable unwritable) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Shards] could not write {}: {}", path,
                    unwritable.toString());
        }
    }

    @Override
    public synchronized void flushProfile() {
        if (loaded) {
            saveThrottle.written(System.currentTimeMillis());
            persist();
        }
    }

    @Override
    public synchronized void reloadProfile() {
        loaded = false;
        snapshot = new Snapshot();
        ensureLoaded();
    }
}
