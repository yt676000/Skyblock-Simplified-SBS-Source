/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.hunting.logic;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ProfileScopedStore;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.skills.hunting.model.HuntingBoxShard;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The last read of the Hunting Box, kept per account + SkyBlock profile so the value is answerable
 * without reopening the menu.
 *
 * <p>Shards belong to one profile, so this is a {@link ProfileScopedStore} like the Bazaar orders and
 * the storage index - it registers itself in its own constructor (registering from the client
 * initialiser forces classes to load before the item registry is bound) and the file lives beside
 * the other per-profile caches.
 *
 * <p><b>The timestamp is part of the data, not decoration.</b> Everything downstream shows the age,
 * because a box read three days ago priced at today's Bazaar is two different vintages of fact in
 * one number, and only the age tells the reader which parts to trust.
 */
public final class HuntingBoxStore implements ProfileScopedStore {

    private static final HuntingBoxStore INSTANCE = new HuntingBoxStore();

    /** Snapshot file name inside the profile directory. */
    private static final String FILE = "hunting_box.json";

    /** What Gson writes: the shard list plus when it was read. */
    private static final class Snapshot {
        List<HuntingBoxShard> shards = new ArrayList<>();
        long readAt;
    }

    private volatile Snapshot snapshot = new Snapshot();
    private boolean loaded;

    private HuntingBoxStore() {
        sbs.modid.client.core.config.ProfileContext.getInstance().register(this);
    }

    public static HuntingBoxStore getInstance() {
        return INSTANCE;
    }

    /** The shards of the last read, newest read wins; empty when the box has never been opened. */
    public List<HuntingBoxShard> shards() {
        ensureLoaded();
        return Collections.unmodifiableList(snapshot.shards);
    }

    /** When the box was last read, epoch ms, or 0 when never. */
    public long readAt() {
        ensureLoaded();
        return snapshot.readAt;
    }

    /** How long ago the box was read, or -1 when it never was. */
    public long ageMs() {
        long at = readAt();
        return at == 0 ? -1 : System.currentTimeMillis() - at;
    }

    /**
     * Replaces the snapshot with a fresh read.
     *
     * <p>Replaces rather than merges on purpose: the box is the authority on what is in it, and a
     * merge would keep a shard the player has since syphoned or fused away for ever.
     */
    public void replace(List<HuntingBoxShard> shards) {
        ensureLoaded();
        Snapshot next = new Snapshot();
        next.shards = new ArrayList<>(shards);
        next.readAt = System.currentTimeMillis();
        snapshot = next;
        persist();
    }

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
                if (stored == null) {
                    return new Snapshot();
                }
                if (stored.shards == null) {
                    stored.shards = new ArrayList<>();
                }
                return stored;
            }
        } catch (Throwable corrupt) {
            // Expected failure mode for a cache: log at info and carry on with an empty box.
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][HuntingBox] could not read {}: {}",
                    path, corrupt.toString());
            return new Snapshot();
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
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][HuntingBox] could not save the box snapshot",
                    unwritable);
        }
    }

    @Override
    public void flushProfile() {
        if (loaded) {
            persist();
        }
    }

    @Override
    public void reloadProfile() {
        loaded = false;
        snapshot = new Snapshot();
        ensureLoaded();
    }
}
