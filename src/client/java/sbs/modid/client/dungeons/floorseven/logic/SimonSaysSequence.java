/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.floorseven.logic;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The Simon Says light order as plain data, so the fold and idle rules can be tested without a
 * world. Buttons are keyed by {@code BlockPos.asLong()}.
 *
 * <p><b>Fold.</b> Each round replays the sequence from the start and adds one step. A light that was
 * already recorded keeps its number; only a light never seen before is appended. A light that stays
 * on across two scans is one flash, not two.
 *
 * <p><b>Idle.</b> Nothing new for {@link #IDLE_RESET_MS} means the device moved on without us.
 * There is no fixed round count: the list grows for as long as the lights keep coming.
 */
public final class SimonSaysSequence {

    /** Forget a sequence nothing has touched for this long. */
    public static final long IDLE_RESET_MS = 90_000L;

    private final List<Long> order = new ArrayList<>();
    private Set<Long> litBefore = Set.of();
    private long lastChangeMs;
    private boolean touched;

    /**
     * One scan's lit buttons.
     *
     * @return true when the recorded order grew
     */
    public boolean observe(Set<Long> lit, long now) {
        boolean grew = false;
        for (Long key : lit) {
            if (!litBefore.contains(key) && !order.contains(key)) {
                order.add(key);
                grew = true;
            }
        }
        if (grew || !lit.equals(litBefore)) {
            lastChangeMs = now;
            touched = true;
        }
        litBefore = lit.isEmpty() ? Set.of() : new HashSet<>(lit);
        return grew;
    }

    /** Whether the sequence has gone quiet for longer than {@link #IDLE_RESET_MS}. */
    public boolean idle(long now) {
        return touched && now - lastChangeMs > IDLE_RESET_MS;
    }

    public int size() {
        return order.size();
    }

    public long get(int index) {
        return order.get(index);
    }

    /** The 0-based step of a button, or -1 when it never lit. */
    public int indexOf(long key) {
        return order.indexOf(key);
    }

    public void clear() {
        order.clear();
        litBefore = Set.of();
        lastChangeMs = 0;
        touched = false;
    }
}
