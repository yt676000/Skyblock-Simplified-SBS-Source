/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.map.logic;

import net.minecraft.core.BlockPos;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.pathfinding.Waypoint;
import sbs.modid.client.core.pathfinding.WaypointStore;

import java.util.List;

/**
 * The one place picked on the Crystal Hollows map: a marker and beam in the world through the shared
 * waypoint renderer, and the direction card on the HUD.
 *
 * <p><b>Display only.</b> The waypoint is published with {@code routable = false}, so the pathfinder
 * never plans a route to it; nothing here moves the player or the camera.
 *
 * <p>Published only when the target changes - {@link WaypointStore#setTransient} invalidates the
 * pathfinder, so republishing per tick would be wasted work.
 */
public final class HollowsTarget {

    /**
     * What is targeted. {@code key} identifies it across frames ("S:JUNGLE_TEMPLE", "M:label@x,y,z")
     * so clicking the same thing again can clear it.
     */
    public record Target(String key, String label, int x, int y, int z) {
    }

    private static final HollowsTarget INSTANCE = new HollowsTarget();

    private Target current;

    private HollowsTarget() {
    }

    public static HollowsTarget getInstance() {
        return INSTANCE;
    }

    public Target current() {
        return current;
    }

    public boolean is(String key) {
        return current != null && current.key().equals(key);
    }

    /** Targets {@code target}, or clears it when it is already the target. */
    public void toggle(Target target) {
        if (target == null || is(target.key())) {
            clear();
            return;
        }
        current = target;
        Waypoint waypoint = new Waypoint(target.label(), new BlockPos(target.x(), target.y(), target.z()),
                WaypointStore.currentDimension(), Waypoint.SOURCE_CH_MAP);
        waypoint.routable = false;
        waypoint.throughWalls = ConfigManager.getInstance().get().map.throughWalls;
        waypoint.showDistance = true;
        WaypointStore.setTransient(Waypoint.SOURCE_CH_MAP, List.of(waypoint));
    }

    public void clear() {
        if (current == null) {
            return;
        }
        current = null;
        WaypointStore.clearTransient(Waypoint.SOURCE_CH_MAP);
    }
}
