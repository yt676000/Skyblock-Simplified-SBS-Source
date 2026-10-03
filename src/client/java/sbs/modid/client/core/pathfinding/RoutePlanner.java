/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.pathfinding;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The decisions priority still makes once every source has a route of its own: which routes run
 * under the cap, which is primary, and how one tick's node budget is shared. Pure and static, so the
 * rules are unit-tested without a world.
 */
final class RoutePlanner {

    /**
     * The smallest slice worth handing a search. Below it the per-step overhead dominates, so when
     * the budget is tight the lowest-priority searches wait a tick instead of all crawling.
     */
    static final int MIN_SLICE = 250;

    private RoutePlanner() {
    }

    /**
     * The sources allowed to run, highest priority first: the first {@code cap} of {@code wanting}.
     * Everything else is paused - lowest priority dropped first.
     */
    static List<RouteSource> admit(Collection<RouteSource> wanting, int cap) {
        List<RouteSource> sorted = new ArrayList<>(wanting);
        sorted.sort(null);   // enum order is priority order
        return sorted.size() <= cap ? sorted : new ArrayList<>(sorted.subList(0, Math.max(0, cap)));
    }

    /** The primary among {@code running}: the highest-priority one, or {@code null} for none. */
    static RouteSource primary(Collection<RouteSource> running) {
        RouteSource best = null;
        for (RouteSource source : running) {
            if (best == null || source.priority() < best.priority()) {
                best = source;
            }
        }
        return best;
    }

    /**
     * Splits one tick's {@code budget} between {@code searching} searches that are already in
     * priority order. Even shares when each is at least {@link #MIN_SLICE}; otherwise the budget goes
     * to as many of the highest-priority searches as can get that much, and the rest get 0 this tick.
     * The shares never sum to more than {@code budget} (bar the one-search floor), which is what keeps
     * four routes inside the time the single route used to spend.
     */
    static int[] slices(int budget, int searching) {
        int[] out = new int[Math.max(0, searching)];
        if (searching <= 0) {
            return out;
        }
        int even = budget / searching;
        if (even >= MIN_SLICE) {
            java.util.Arrays.fill(out, even);
            return out;
        }
        int funded = Math.max(1, budget / MIN_SLICE);
        int share = Math.max(budget / funded, 1);
        for (int i = 0; i < Math.min(funded, searching); i++) {
            out[i] = share;
        }
        return out;
    }
}
