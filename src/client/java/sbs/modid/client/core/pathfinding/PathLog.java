/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.pathfinding;

import sbs.modid.SkyblockSimplifiedSBS;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The {@code [SBS][Path]} log: one summary line per minute instead of a line per search.
 *
 * <p>A moving target re-plans every few seconds, and a line per search, per phase switch and per
 * "no route" filled a session log with thousands of lines that said the same thing. The summary keeps
 * what those lines were read for - how many searches, how long they ran, how they ended - plus the
 * latest "no route" as an example to check a report against.
 *
 * <p>Client thread only (the searches run there), so no locking.
 */
final class PathLog {

    static final long INTERVAL_MS = 60_000L;

    private static final Map<RouteSource, Integer> SEARCHES = new EnumMap<>(RouteSource.class);
    private static final Map<String, Integer> MODES = new TreeMap<>();
    private static final Map<String, Integer> SWITCHES = new TreeMap<>();
    private static int searches;
    private static int reached;
    private static long totalMs;
    private static long maxMs;
    private static long nodes;
    private static int noRoutes;
    private static String lastNoRoute = "";
    private static long windowStart = -1;

    private PathLog() {
    }

    static void searched(RouteSource source, String mode, long ms, long expanded, boolean reachedGoal) {
        searches++;
        SEARCHES.merge(source, 1, Integer::sum);
        MODES.merge(mode, 1, Integer::sum);
        totalMs += ms;
        maxMs = Math.max(maxMs, ms);
        nodes += expanded;
        if (reachedGoal) {
            reached++;
        }
    }

    static void switched(String to) {
        SWITCHES.merge(to, 1, Integer::sum);
    }

    static void noRoute(String detail) {
        noRoutes++;
        lastNoRoute = detail;
    }

    /** Called every tick; writes the summary once a minute, and nothing for a quiet minute. */
    static void flush(long now) {
        if (windowStart < 0) {
            windowStart = now;
            return;
        }
        if (now - windowStart < INTERVAL_MS) {
            return;
        }
        if (searches > 0 || noRoutes > 0) {
            SkyblockSimplifiedSBS.LOGGER.info(String.format(Locale.ROOT,
                    "[SBS][Path] last %ds: %d searches (%d reached) by source %s, modes %s, switches %s, "
                            + "search wall time avg %d ms max %d ms, %d nodes, budget %d nodes/tick, "
                            + "%d without a route%s",
                    (now - windowStart) / 1000, searches, reached, SEARCHES, MODES, SWITCHES,
                    searches == 0 ? 0 : totalMs / searches, maxMs, nodes,
                    PathfindingManager.getInstance().nodeBudget(), noRoutes,
                    lastNoRoute.isEmpty() ? "" : " (latest: " + lastNoRoute + ")"));
        }
        reset(now);
    }

    private static void reset(long now) {
        SEARCHES.clear();
        MODES.clear();
        SWITCHES.clear();
        searches = 0;
        reached = 0;
        totalMs = 0;
        maxMs = 0;
        nodes = 0;
        noRoutes = 0;
        lastNoRoute = "";
        windowStart = now;
    }
}
