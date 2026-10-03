/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.quest.logic;

import net.minecraft.core.BlockPos;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.pathfinding.PathRouting;
import sbs.modid.client.core.pathfinding.PathfindingManager;
import sbs.modid.client.core.pathfinding.Waypoint;
import sbs.modid.client.core.pathfinding.WaypointStore;
import sbs.modid.client.helper.quest.model.Quest;
import sbs.modid.client.helper.quest.model.QuestIslands;

import java.util.List;

/**
 * Publishes the current quest step's location as a waypoint, so the existing pathfinding module
 * routes to it – rather than the Quest Guide growing a second router beside it.
 *
 * <p>Quest waypoints are tagged {@link Waypoint#SOURCE_QUEST}, which is what lets
 * {@link PathRouting} prefer the objective over a closer personal marker and lets this class replace
 * its own waypoint without ever touching one the player placed.
 */
public final class QuestWaypoints {

    private QuestWaypoints() {
    }

    /**
     * Makes the current step's location the one quest waypoint. Cheap and idempotent – it returns
     * immediately when nothing changed, so it is safe to call every tick.
     */
    public static void sync() {
        if (!ConfigManager.getInstance().get().questGuide.showWaypoint) {
            clear();
            return;
        }
        QuestTracker tracker = QuestTracker.getInstance();
        Quest.QuestStep step = tracker.currentStep();
        Quest.QuestWaypoint target = step == null ? null : step.waypoint;
        if (target == null) {
            // Steps like "bring it to Juliette" have no location - drop the stale one rather than
            // leaving the player routed at the last NPC.
            clear();
            return;
        }
        // The coordinates only mean anything on their own island: every SkyBlock island shares one
        // Minecraft dimension but has its own coordinate space, so publishing a Crimson Isle
        // waypoint while on the Hub would route to an arbitrary spot instead of failing visibly.
        if (!QuestIslands.onIslandOf(tracker.quest(), target)) {
            clear();
            return;
        }
        String name = target.label == null ? "Objective" : target.label;
        Waypoint existing = questWaypoint();
        if (existing != null && name.equals(existing.name)
                && existing.x == target.x && existing.y == target.y && existing.z == target.z) {
            return; // already current
        }
        clear();
        WaypointStore.all().add(new Waypoint(name, new BlockPos(target.x, target.y, target.z),
                WaypointStore.currentDimension(), Waypoint.SOURCE_QUEST));
        ConfigManager.getInstance().save();
        PathfindingManager.getInstance().invalidate();
    }

    /** Removes the quest waypoint, leaving the player's own alone. */
    public static void clear() {
        List<Waypoint> all = WaypointStore.all();
        if (all.removeIf(Waypoint::isQuest)) {
            ConfigManager.getInstance().save();
            PathfindingManager.getInstance().invalidate();
        }
    }

    private static Waypoint questWaypoint() {
        for (Waypoint waypoint : WaypointStore.all()) {
            if (waypoint.isQuest()) {
                return waypoint;
            }
        }
        return null;
    }
}
