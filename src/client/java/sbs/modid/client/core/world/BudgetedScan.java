/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.world;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * A block scan over a box, split across ticks: each {@link #step} visits at most a fixed number of
 * blocks or spends at most a fixed time, then leaves a cursor for the next tick.
 *
 * <p><b>Order.</b> Chunk column by chunk column (X-major, then Z), and inside a column X, Z, Y. A
 * scanner that depends on visit order sorts the hits itself.
 *
 * <p><b>Unchanged chunks.</b> Each column's hits are kept with the {@link ChunkRevisions} revision
 * read when its visit started and the part of the box it covered. The next pass reuses them without
 * reading a block when the revision is the same, non-zero, and the covered part is identical. A
 * change during the visit leaves the cached revision behind the live one, so the next pass rescans.
 *
 * <p><b>Generation.</b> {@link #begin} and {@link #step} take the caller's context generation
 * (usually {@link WorldTransitions#generation()}). A different number drops the cache and restarts
 * the pass from the first block - no hit from an old context ever reaches a result.
 *
 * <p>Main thread only. Steady state (a pass whose chunks are all cached, or a running pass) allocates
 * nothing; a column seen for the first time allocates its cache entry once.
 */
public final class BudgetedScan {

    /** Reads one block; returns a tag {@code >= 0} to record it as a hit, or {@code -1} to skip it. */
    @FunctionalInterface
    public interface Probe {
        int classify(int x, int y, int z);
    }

    /** Which columns of the box are visited at all. Must depend on the coordinates only. */
    @FunctionalInterface
    public interface ColumnFilter {
        boolean accepts(int x, int z);
    }

    /** The revision source; {@link ChunkRevisions#revision} in game. */
    @FunctionalInterface
    public interface Revisions {
        int revision(int chunkX, int chunkZ);
    }

    public static final ColumnFilter ALL_COLUMNS = (x, z) -> true;

    /** How often (blocks) the time budget is checked; {@code System.nanoTime} is not free. */
    private static final int CLOCK_EVERY = 256;

    /** One chunk column's cached hits, as {x, y, z, tag} quadruples. */
    private static final class Column {
        final long key;
        int revision;
        int minX, maxX, minZ, maxZ, minY, maxY;
        long pass;
        final IntArrayList hits = new IntArrayList();

        Column(long key) {
            this.key = key;
        }
    }

    private final Probe probe;
    private final ColumnFilter filter;
    private final Revisions revisions;
    private final int maxBlocks;
    private final long maxNanos;
    private final LongSupplier clock;

    private final Long2ObjectOpenHashMap<Column> columns = new Long2ObjectOpenHashMap<>();
    /** The same columns as a list, so pruning walks them without an iterator. */
    private final List<Column> live = new ArrayList<>();
    private final IntArrayList result = new IntArrayList();

    private long generation;
    private long pass;
    private boolean active;
    private boolean complete;
    private int minX, minY, minZ, maxX, maxY, maxZ;

    /** Cursor: current chunk column, and the block inside it; {@code column == null} between columns. */
    private int chunkX, chunkZ;
    private Column column;
    /** Revision read when {@link #column} was opened; stored on it only once its visit completes. */
    private int columnRevision;
    private int x, y, z;

    private int lastBlocks;
    private int chunksSkipped;
    private int chunksScanned;

    /** Perf: the name shown in the overlay, and the most blocks one step read in this / the last second. */
    private final String name;
    private int peakBlocks;
    private int peakBlocksPrevious;
    private long peakSecond;
    /** Chunk columns skipped / visited by the last completed pass. */
    private int lastPassSkipped;
    private int lastPassTotal;

    /** Every named scan, for the Perf overlay. Main thread only. */
    private static final List<BudgetedScan> NAMED = new ArrayList<>();

    /**
     * A scan reported in the Perf overlay under {@code name}. Create once per scanner (it holds the
     * column cache); the overlay keeps a reference for the game's lifetime.
     */
    public BudgetedScan(String name, Probe probe, ColumnFilter filter, Revisions revisions, int maxBlocksPerStep,
                        long maxNanosPerStep) {
        this(name, probe, filter, revisions, maxBlocksPerStep, maxNanosPerStep, System::nanoTime);
        NAMED.add(this);
    }

    BudgetedScan(Probe probe, ColumnFilter filter, Revisions revisions, int maxBlocksPerStep,
                 long maxNanosPerStep) {
        this(null, probe, filter, revisions, maxBlocksPerStep, maxNanosPerStep, System::nanoTime);
    }

    BudgetedScan(Probe probe, ColumnFilter filter, Revisions revisions, int maxBlocksPerStep,
                 long maxNanosPerStep, LongSupplier clock) {
        this(null, probe, filter, revisions, maxBlocksPerStep, maxNanosPerStep, clock);
    }

    private BudgetedScan(String name, Probe probe, ColumnFilter filter, Revisions revisions, int maxBlocksPerStep,
                         long maxNanosPerStep, LongSupplier clock) {
        this.name = name;
        this.probe = probe;
        this.filter = filter;
        this.revisions = revisions;
        this.maxBlocks = maxBlocksPerStep;
        this.maxNanos = maxNanosPerStep;
        this.clock = clock;
    }

    /** Starts a pass over the inclusive box, dropping any pass in progress. */
    public void begin(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, long generation) {
        if (generation != this.generation) {
            dropCache();
            this.generation = generation;
        }
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        restart();
    }

    private void restart() {
        pass++;
        result.clear();
        chunksSkipped = 0;
        chunksScanned = 0;
        chunkX = minX >> 4;
        chunkZ = minZ >> 4;
        column = null;
        complete = false;
        active = minX <= maxX && minZ <= maxZ && minY <= maxY;
        if (!active) {
            complete = true;
        }
    }

    /** Whether a pass is running (begun, not yet complete). */
    public boolean active() {
        return active;
    }

    /**
     * Advances the pass within the budget. Returns {@code true} on the step that completes it; the
     * hits are then readable until the next {@link #begin}. A generation change restarts the pass.
     */
    public boolean step(long generation) {
        if (!active) {
            return false;
        }
        if (generation != this.generation) {
            dropCache();
            this.generation = generation;
            restart();
        }
        int blocks = 0;
        long deadline = clock.getAsLong() + maxNanos;
        while (true) {
            if (column == null) {
                if (chunkX > (maxX >> 4)) {
                    finish();
                    record(blocks);
                    return true;
                }
                if (openColumn()) {
                    continue;   // reused from cache, cursor already moved on
                }
            }
            // Visit blocks of the open column until it ends or the budget is spent.
            int cMaxX = column.maxX, cMaxZ = column.maxZ, cMinZ = column.minZ, cMinY = column.minY, cMaxY = column.maxY;
            while (x <= cMaxX) {
                if (y == cMinY && !filter.accepts(x, z)) {
                    y = cMaxY + 1;
                }
                while (y <= cMaxY) {
                    int tag = probe.classify(x, y, z);
                    if (tag >= 0) {
                        column.hits.add(x);
                        column.hits.add(y);
                        column.hits.add(z);
                        column.hits.add(tag);
                    }
                    y++;
                    blocks++;
                    if (blocks >= maxBlocks
                            || (blocks % CLOCK_EVERY == 0 && clock.getAsLong() - deadline >= 0)) {
                        record(blocks);
                        return false;
                    }
                }
                y = cMinY;
                if (++z > cMaxZ) {
                    z = cMinZ;
                    x++;
                }
            }
            column.revision = columnRevision;
            result.addAll(column.hits);
            column = null;
            advanceChunk();
        }
    }

    /**
     * Opens the column at the cursor. Returns {@code true} when its cached hits were reused (cursor
     * advanced), {@code false} when its blocks must be visited.
     */
    private boolean openColumn() {
        int cMinX = Math.max(minX, chunkX << 4), cMaxX = Math.min(maxX, (chunkX << 4) + 15);
        int cMinZ = Math.max(minZ, chunkZ << 4), cMaxZ = Math.min(maxZ, (chunkZ << 4) + 15);
        int revision = revisions.revision(chunkX, chunkZ);
        long key = ChunkRevisions.key(chunkX, chunkZ);
        Column c = columns.get(key);
        if (c == null) {
            c = new Column(key);
            columns.put(key, c);
            live.add(c);
        } else if (revision != 0 && c.revision == revision && c.minX == cMinX && c.maxX == cMaxX
                && c.minZ == cMinZ && c.maxZ == cMaxZ && c.minY == minY && c.maxY == maxY) {
            c.pass = pass;
            result.addAll(c.hits);
            chunksSkipped++;
            advanceChunk();
            return true;
        }
        c.revision = 0;   // a column left half-visited must never be reused
        columnRevision = revision;
        c.minX = cMinX;
        c.maxX = cMaxX;
        c.minZ = cMinZ;
        c.maxZ = cMaxZ;
        c.minY = minY;
        c.maxY = maxY;
        c.pass = pass;
        c.hits.clear();
        column = c;
        x = cMinX;
        z = cMinZ;
        y = minY;
        chunksScanned++;
        return false;
    }

    private void advanceChunk() {
        if (++chunkZ > (maxZ >> 4)) {
            chunkZ = minZ >> 4;
            chunkX++;
        }
    }

    private void record(int blocks) {
        lastBlocks = blocks;
        long second = System.currentTimeMillis() / 1000L;
        if (second != peakSecond) {
            peakBlocksPrevious = second == peakSecond + 1 ? peakBlocks : 0;
            peakBlocks = 0;
            peakSecond = second;
        }
        if (blocks > peakBlocks) {
            peakBlocks = blocks;
        }
    }

    /** Ends the pass and forgets the columns it did not touch, so the cache stays the box's size. */
    private void finish() {
        active = false;
        complete = true;
        lastPassSkipped = chunksSkipped;
        lastPassTotal = chunksSkipped + chunksScanned;
        for (int i = live.size() - 1; i >= 0; i--) {
            Column c = live.get(i);
            if (c.pass != pass) {
                columns.remove(c.key);
                Column last = live.remove(live.size() - 1);
                if (i < live.size()) {
                    live.set(i, last);
                }
            }
        }
    }

    private void dropCache() {
        columns.clear();
        live.clear();
        column = null;
    }

    /** Stops any pass and forgets every cached column and hit. */
    public void reset() {
        dropCache();
        result.clear();
        active = false;
        complete = false;
        lastBlocks = 0;
        chunksSkipped = 0;
        chunksScanned = 0;
    }

    /** Whether the last pass ran to its end (hits are valid). */
    public boolean complete() {
        return complete;
    }

    public int hitCount() {
        return result.size() >> 2;
    }

    public int hitX(int i) {
        return result.getInt(i << 2);
    }

    public int hitY(int i) {
        return result.getInt((i << 2) + 1);
    }

    public int hitZ(int i) {
        return result.getInt((i << 2) + 2);
    }

    public int hitTag(int i) {
        return result.getInt((i << 2) + 3);
    }

    /** Blocks read by the last {@link #step}. */
    public int lastBlocks() {
        return lastBlocks;
    }

    /** Chunk columns reused unread in the current / last pass. */
    public int chunksSkipped() {
        return chunksSkipped;
    }

    /** Chunk columns whose blocks were read in the current / last pass. */
    public int chunksScanned() {
        return chunksScanned;
    }

    /**
     * One line per named scan for the Perf overlay: the most blocks a single tick read over the last
     * one to two seconds, and how many chunk columns the last pass skipped. COLD (overlay only).
     */
    public static String perfSummary() {
        if (NAMED.isEmpty()) {
            return "-";
        }
        long second = System.currentTimeMillis() / 1000L;
        StringBuilder out = new StringBuilder();
        for (BudgetedScan scan : NAMED) {
            int peak = 0;
            if (scan.peakSecond == second) {
                peak = Math.max(scan.peakBlocks, scan.peakBlocksPrevious);
            } else if (scan.peakSecond == second - 1) {
                peak = scan.peakBlocks;
            }
            if (!out.isEmpty()) {
                out.append(", ");
            }
            out.append(scan.name).append(' ').append(peak).append(" blk/tick, ")
                    .append(scan.lastPassSkipped).append('/').append(scan.lastPassTotal).append(" chunks skipped");
        }
        return out.toString();
    }
}
