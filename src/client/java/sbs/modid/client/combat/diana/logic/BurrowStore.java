/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.logic;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import sbs.modid.client.combat.diana.model.BurrowKind;
import sbs.modid.client.combat.diana.model.BurrowRecord;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every burrow this client currently believes in, and the short blacklist that stops a burrow that
 * has just been dug out from immediately coming back.
 *
 * <h2>The block is the identity</h2>
 *
 * <p>A burrow is its block and nothing else. Particles arrive above it, chat mentions it without
 * coordinates, and three different subsystems talk about it - so they can only agree if all of them
 * name it the same way. {@link #blockOf} is that one way, and every consumer in this package goes
 * through it. Getting the rounding consistent matters more than getting it right: a half-block error
 * everywhere is invisible, a half-block error in one place makes two features disagree about whether
 * they mean the same burrow.
 *
 * <h2>The one-second blacklist is not an optimisation</h2>
 *
 * <p>When a burrow is dug out the server says so with a particle, and it keeps sending the burrow's
 * own particles for a moment afterwards - they were already in flight. Without
 * {@link #suppress}/{@link #suppressed} the detector would delete the burrow and then immediately
 * recreate it from that tail, once per packet, and the marker would flicker instead of leaving.
 *
 * <h2>Nothing here survives an instance boundary</h2>
 *
 * <p>Burrows belong to one Hub on one server. {@link #clear()} runs on world change, and a store
 * that survived a hop would report the previous lobby's burrows with complete confidence - which is
 * the failure mode that is hardest to notice, because the markers look exactly like real ones.
 */
public final class BurrowStore {

    private static final BurrowStore INSTANCE = new BurrowStore();

    /**
     * How long a dug-out block refuses to become a burrow again.
     *
     * <p>Long enough to swallow the particles still in flight for the burrow that just went, short
     * enough that a genuinely new burrow appearing on the same block is not missed. One second is
     * what the behaviour was described with and it is the value a capture would revise.
     */
    private static final long SUPPRESS_MS = 1_000L;

    /**
     * Concurrent because the writer is the packet handler and the readers are the tick and the
     * render pass. The packet handler runs on the main thread by the time it reaches us - the
     * mixin injects at TAIL, after netty has re-dispatched - but the render pass does not, and a
     * plain map iterated while a packet lands is a crash nobody can reproduce on demand.
     */
    private final Map<BlockPos, BurrowRecord> burrows = new ConcurrentHashMap<>();

    /** Block -> when it was dug out. Pruned lazily on read; a handful of entries at most. */
    private final Map<BlockPos, Long> recentlyRemoved = new ConcurrentHashMap<>();

    private BurrowStore() {
    }

    public static BurrowStore getInstance() {
        return INSTANCE;
    }

    /**
     * The block a particle at these coordinates belongs to.
     *
     * <p>Floor to a block, then down one: the particle hovers above the burrow, and the burrow is
     * the block underneath it. The single place this conversion happens.
     */
    public static BlockPos blockOf(double x, double y, double z) {
        return BlockPos.containing(x, y, z).below();
    }

    /** The record at this block, creating one if this is the first time it has been mentioned. */
    public BurrowRecord recordAt(BlockPos pos) {
        return burrows.computeIfAbsent(pos, BurrowRecord::new);
    }

    /** The record at this block, or {@code null}. Never creates. */
    public BurrowRecord peek(BlockPos pos) {
        return pos == null ? null : burrows.get(pos);
    }

    /** Whether a burrow is known at this block, whatever is known about it. */
    public boolean has(BlockPos pos) {
        return pos != null && burrows.containsKey(pos);
    }

    /** Every burrow, in no particular order. A copy: callers iterate while packets land. */
    public Collection<BurrowRecord> all() {
        return new ArrayList<>(burrows.values());
    }

    /** Every burrow whose kind is known - the ones worth walking to. */
    public List<BurrowRecord> classified() {
        List<BurrowRecord> out = new ArrayList<>();
        for (BurrowRecord record : burrows.values()) {
            if (record.classified()) {
                out.add(record);
            }
        }
        return out;
    }

    public int size() {
        return burrows.size();
    }

    /** Drops the record at this block. Does not suppress - {@link #remove} is the usual pairing. */
    public BurrowRecord forget(BlockPos pos) {
        return pos == null ? null : burrows.remove(pos);
    }

    /**
     * Drops the record and refuses the block for a moment.
     *
     * <p>The pairing every removal path wants: the burrow is gone <i>and</i> its trailing particles
     * must not bring it back. Removing without suppressing is what makes a marker flicker.
     */
    public void remove(BlockPos pos) {
        if (pos == null) {
            return;
        }
        burrows.remove(pos);
        suppress(pos);
    }

    /** Refuses this block for {@link #SUPPRESS_MS} without touching whatever is recorded there. */
    public void suppress(BlockPos pos) {
        if (pos != null) {
            recentlyRemoved.put(pos, System.currentTimeMillis());
        }
    }

    /** Whether this block is currently refused. Expired entries are dropped as they are found. */
    public boolean suppressed(BlockPos pos) {
        if (pos == null) {
            return false;
        }
        Long at = recentlyRemoved.get(pos);
        if (at == null) {
            return false;
        }
        if (System.currentTimeMillis() - at <= SUPPRESS_MS) {
            return true;
        }
        recentlyRemoved.remove(pos, at);
        return false;
    }

    /**
     * Sets a burrow's kind, keeping everything already known about it.
     *
     * <p>Reuses the record rather than replacing it, because replacing is what loses the dig count -
     * and a burrow that forgets it has already been dug once is a marker that disappears while the
     * player is still working on it.
     */
    public BurrowRecord classify(BlockPos pos, BurrowKind kind) {
        BurrowRecord record = recordAt(pos);
        if (record.kind != kind) {
            record.kind = kind;
            record.touch();
        }
        return record;
    }

    /** Everything goes: world change, server hop, island change, or the player asking. */
    public void clear() {
        burrows.clear();
        recentlyRemoved.clear();
    }

    /** One line for the debug readout. */
    public String status() {
        int classified = classified().size();
        return burrows.size() + " burrow(s), " + classified + " classified, "
                + recentlyRemoved.size() + " block(s) suppressed";
    }

    /** Every record and every suppressed block, for the guard's error report. Changes nothing. */
    public JsonObject snapshot() {
        JsonArray records = new JsonArray();
        for (BurrowRecord record : burrows.values()) {
            if (records.size() >= DianaSnapshot.MAX_ENTRIES) {
                break;
            }
            JsonObject r = new JsonObject();
            r.addProperty("pos", DianaSnapshot.pos(record.pos));
            r.addProperty("kind", String.valueOf(record.kind));
            r.addProperty("seenMarker", record.seenMarker);
            r.addProperty("seenFootstep", record.seenFootstep);
            r.addProperty("timesDug", record.timesDug);
            r.addProperty("discoveredAt", record.discoveredAt);
            r.addProperty("updatedAt", record.updatedAt);
            records.add(r);
        }
        JsonObject removed = new JsonObject();
        recentlyRemoved.forEach((pos, at) -> removed.addProperty(DianaSnapshot.pos(pos), at));
        JsonObject out = new JsonObject();
        out.addProperty("count", burrows.size());
        out.add("burrows", records);
        out.add("recentlyRemoved", removed);
        return out;
    }
}
