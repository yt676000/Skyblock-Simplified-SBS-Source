/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.floorseven.logic;

import java.util.Arrays;

/**
 * The state of the phase-3 "Shoot the Target" device, built only from block changes - no Minecraft
 * types, so a synthetic sequence of changes can be replayed in a test.
 *
 * <p>Cells are packed block positions ({@code BlockPos.asLong}). The model is the ESTIMATED one
 * (see {@code docs/features/f7-shoot-the-target.md}): one cell at a time turns into the target block,
 * and a target that turns back has been hit. Every turn-back is counted as a hit, because the client
 * cannot see the arrow land; whether a target can also time out without a hit is an open question
 * the probe log answers, and the count would then run high.
 *
 * <p>More than one cell may be lit at once: a multi-block packet can carry the next target before
 * the old one's turn-back, so the lit cells are a small set, and the <b>current</b> target is the one
 * lit most recently.
 */
public final class ShootTargetBoard {

    /** Hits the device is assumed to need. ESTIMATED - the completion log records the real count. */
    public static final int ESTIMATED_HITS = 9;

    /** No real device has more cells than this; a full array means the guess is wrong, not a crash. */
    static final int MAX_CELLS = 32;

    /** What one block change did to the board, for the caller's once-per-change log line. */
    public enum Change {
        /** Not a lit/unlit transition this board cares about. */
        NONE,
        /** The very first target of this device - the caller dumps the grid once. */
        FIRST_TARGET,
        /** A new target lit up. */
        TARGET,
        /** A lit target turned back: counted as a hit. */
        HIT
    }

    private final long[] lit = new long[MAX_CELLS];
    private int litCount;
    private final long[] hitCells = new long[MAX_CELLS];
    private int hitCellCount;
    private int hits;
    private boolean seen;
    private boolean completed;
    private long lastEventMs;
    /** Bumped on every visible change, so the renderer rebuilds its cached marks only then. */
    private int revision;

    /** A cell became the target block. */
    public Change onLit(long cell, long nowMs) {
        if (completed || indexOf(lit, litCount, cell) >= 0) {
            return Change.NONE;
        }
        if (litCount == MAX_CELLS) {
            return Change.NONE;
        }
        lit[litCount++] = cell;
        lastEventMs = nowMs;
        revision++;
        if (!seen) {
            seen = true;
            return Change.FIRST_TARGET;
        }
        return Change.TARGET;
    }

    /** A cell stopped being the target block. Only a cell this board saw light up counts. */
    public Change onUnlit(long cell, long nowMs) {
        int at = indexOf(lit, litCount, cell);
        if (completed || at < 0) {
            return Change.NONE;
        }
        // Keep the order: the last element stays "most recently lit".
        System.arraycopy(lit, at + 1, lit, at, litCount - at - 1);
        litCount--;
        hits++;
        if (indexOf(hitCells, hitCellCount, cell) < 0 && hitCellCount < MAX_CELLS) {
            hitCells[hitCellCount++] = cell;
        }
        lastEventMs = nowMs;
        revision++;
        return Change.HIT;
    }

    /**
     * The server said a device was completed. Accepted only if this board had a change within
     * {@code windowMs} - another device finishing elsewhere in the section must not close this one.
     *
     * @return whether the board took it as its own completion
     */
    public boolean onDeviceCompleted(long nowMs, long windowMs) {
        if (!seen || completed || nowMs - lastEventMs > windowMs) {
            return false;
        }
        completed = true;
        litCount = 0;
        revision++;
        return true;
    }

    /** Whether nothing has happened for {@code idleMs} - the device has been left behind. */
    public boolean idle(long nowMs, long idleMs) {
        return seen && nowMs - lastEventMs > idleMs;
    }

    public void reset() {
        if (!seen && !completed) {
            return;
        }
        litCount = 0;
        hitCellCount = 0;
        hits = 0;
        seen = false;
        completed = false;
        lastEventMs = 0;
        revision++;
    }

    // ---- read side ----------------------------------------------------------------------------

    public boolean hasTarget() {
        return litCount > 0;
    }

    /** The current target (most recently lit); only meaningful while {@link #hasTarget()}. */
    public long target() {
        return lit[litCount - 1];
    }

    public int hits() {
        return hits;
    }

    public int hitCellCount() {
        return hitCellCount;
    }

    public long hitCell(int index) {
        return hitCells[index];
    }

    /** Whether a cell has been hit and is not the current target again. */
    public boolean wasHit(long cell) {
        return indexOf(hitCells, hitCellCount, cell) >= 0;
    }

    public boolean seen() {
        return seen;
    }

    public boolean completed() {
        return completed;
    }

    public int revision() {
        return revision;
    }

    private static int indexOf(long[] cells, int count, long cell) {
        for (int i = 0; i < count; i++) {
            if (cells[i] == cell) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public String toString() {
        return "ShootTargetBoard[hits=" + hits + ", lit=" + Arrays.toString(Arrays.copyOf(lit, litCount))
                + ", completed=" + completed + "]";
    }
}
