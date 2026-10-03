/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.secretroutes.model;

import java.util.ArrayList;
import java.util.List;

/**
 * One named secret route inside a room: an ordered set of {@link SecretWaypoint}s, plus an optional
 * recorded walk {@link #trail} and the {@link #breakerBlocks} logged by a Dungeon Breaker scan. Every
 * position is room-relative in the canonical frame, so a route is reusable across runs regardless of
 * the room's rotation. Multiple routes can share a room ("Fast", "Safe", "No AOTV").
 */
public final class SecretRoute {

    public String name = "Route";
    public List<SecretWaypoint> waypoints = new ArrayList<>();

    /**
     * The recorded walk path as canonical block positions ({@code [x,y,z]} each), sampled while a
     * scan was running. Rendered as the movement line via the shared pathfinding renderer; empty
     * when the route was built purely from placed waypoints.
     */
    public List<int[]> trail = new ArrayList<>();

    /** Blocks logged by the Dungeon Breaker scan, in break order. */
    public List<BreakerBlock> breakerBlocks = new ArrayList<>();

    public SecretRoute() {
    }

    public SecretRoute(String name) {
        this.name = name == null || name.isBlank() ? "Route" : name.trim();
    }

    /** One block removed by the Dungeon Breaker tool: canonical position, type, order and time. */
    public static final class BreakerBlock {
        public int relX;
        public int relY;
        public int relZ;
        /** The block registry id at break time, e.g. {@code minecraft:cobblestone}. */
        public String blockId = "";
        /** 0-based break order, so the render can number them. */
        public int order;
        /** Client epoch millis when it was broken (kept for later replay / debugging). */
        public long timestamp;

        public BreakerBlock() {
        }
    }

    /** Next free waypoint index (append order). */
    public int nextIndex() {
        int max = -1;
        for (SecretWaypoint waypoint : waypoints) {
            max = Math.max(max, waypoint.index);
        }
        return max + 1;
    }

    /** Re-numbers every waypoint 0..n by its current list order (after a delete / reorder). */
    public void renumber() {
        for (int i = 0; i < waypoints.size(); i++) {
            waypoints.get(i).index = i;
        }
    }
}
