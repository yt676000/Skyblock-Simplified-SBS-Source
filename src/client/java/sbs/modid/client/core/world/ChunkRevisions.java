/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.world;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

/**
 * A revision per loaded chunk column, bumped whenever the server changes a block in it, sends it
 * or forgets it. A block scan that remembers the revision it saw can skip a chunk whose revision
 * has not moved since - nothing in it can have changed.
 *
 * <p>Fed from {@code ChunkRevisionsMixin} (TAIL of the four chunk/block packet handlers, so main
 * thread only and after vanilla applied the change). Client-side predictions that never round-trip
 * through a packet are not seen; every scanner here looks for server-placed blocks.
 *
 * <p>{@code 0} means "unknown" - never loaded, forgotten, or cleared by {@link WorldTransitions} -
 * and must never be treated as unchanged. Revisions come from one counter that is never reset, so
 * a chunk reloaded after a world change can never repeat a revision a scanner cached before it.
 */
public final class ChunkRevisions {

    /** Packed chunk column (see {@link #key}) to its revision. Default {@code 0} = unknown. */
    private static final Long2IntOpenHashMap REVISIONS = new Long2IntOpenHashMap();

    private static int counter;

    private ChunkRevisions() {}

    /** The chunk's current revision, or {@code 0} when it is not known to be loaded. */
    public static int revision(int chunkX, int chunkZ) {
        return REVISIONS.get(key(chunkX, chunkZ));
    }

    /** A block in the chunk changed, or the chunk was (re)sent. */
    public static void bump(int chunkX, int chunkZ) {
        if (++counter <= 0) {
            counter = 1;   // wrapped after 2^31 changes; 0 must stay "unknown"
        }
        REVISIONS.put(key(chunkX, chunkZ), counter);
    }

    /** The chunk left the client: its blocks are unknown until it is sent again. */
    public static void forget(int chunkX, int chunkZ) {
        REVISIONS.remove(key(chunkX, chunkZ));
    }

    /** World transition: every chunk the old world had is gone. The counter keeps running. */
    public static void clear() {
        REVISIONS.clear();
    }

    /** Same packing as {@code ChunkPos.pack}. */
    static long key(int chunkX, int chunkZ) {
        return (chunkX & 0xFFFFFFFFL) | ((long) chunkZ << 32);
    }
}
