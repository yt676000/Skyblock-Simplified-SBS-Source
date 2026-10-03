/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.foraging.logic;

import sbs.modid.client.skills.foraging.model.PresetKind;
import sbs.modid.client.skills.foraging.model.WaypointPresetData;

import java.util.List;

/**
 * Snaps the block the player clicked to the nearest shipped honey tree.
 *
 * <p><b>Why the identity comes from here and not from chat.</b> A chat line can say that a smear
 * succeeded; it cannot say which of six trees it was. The click carries a position and the position
 * is the only thing that identifies the tree, so this is where identity is decided.
 *
 * <p><b>It binds to {@link PresetKind#TREE}, not to the two group ids.</b> That is the structural
 * guarantee {@code PresetKind} already documents - a hive can never become a tree timer at any
 * tolerance - and it is also what makes a third honey island a data change rather than a code
 * change.
 *
 * <p><b>Horizontal and vertical tolerances are separate, because a tree is not a sphere.</b> The
 * shipped coordinate is one point somewhere in a trunk that the player may click anywhere along,
 * including well above the recorded y. One radius that was generous enough vertically would reach
 * the next tree horizontally, and reaching the next tree is the failure that silently resets
 * somebody else's timer.
 *
 * <p><b>No Minecraft imports</b>, so the snapping rules are exercised in {@code src/test} rather
 * than only on Galatea - the same reason {@code SweepParser} has none. The caller passes the groups
 * that already belong here, so the island question stays with
 * {@link WaypointPresetDatabase#activeHere()} and is not answered a second time by hand.
 */
public final class HoneyTreeCatalog {

    /**
     * How far above or below the recorded point a click still counts, in blocks.
     *
     * <p>Fixed rather than configurable: the horizontal tolerance is the one that decides whether
     * two trees can be confused, and it is the one worth exposing. This number only has to be
     * taller than a tree.
     */
    public static final int VERTICAL_TOLERANCE = 24;

    /** A click resolved to a shipped tree. */
    public record Match(String groupId, String pointId, String name, int x, int y, int z,
                        double horizontalDistance) {
    }

    private HoneyTreeCatalog() {
    }

    /**
     * The nearest tree point to {@code (x, y, z)} within tolerance, or {@code null} for none.
     *
     * @param groupsHere the preset groups that belong to the island the player is standing on
     * @param tolerance  how far away horizontally a click may be, in blocks
     */
    public static Match snap(List<WaypointPresetData.Group> groupsHere,
                             int x, int y, int z, int tolerance) {
        if (groupsHere == null || groupsHere.isEmpty() || tolerance <= 0) {
            return null;
        }
        double limit = (double) tolerance * tolerance;
        Match best = null;
        double bestDistance = Double.MAX_VALUE;
        for (WaypointPresetData.Group group : groupsHere) {
            if (group == null || group.kind() != PresetKind.TREE) {
                continue;
            }
            for (WaypointPresetData.Point point : group.points) {
                if (point == null || !point.valid()) {
                    continue;
                }
                if (Math.abs(point.y - y) > VERTICAL_TOLERANCE) {
                    continue;
                }
                double dx = (double) point.x - x;
                double dz = (double) point.z - z;
                double squared = dx * dx + dz * dz;
                if (squared > limit || squared >= bestDistance) {
                    continue;
                }
                bestDistance = squared;
                best = new Match(group.id, point.id,
                        point.name == null || point.name.isBlank() ? group.name : point.name,
                        point.x, point.y, point.z, Math.sqrt(squared));
            }
        }
        return best;
    }

    /**
     * The shipped point with this id, or {@code null} - how a running timer re-reads its label so a
     * corrected data file corrects the display without touching anybody's stored state.
     */
    public static WaypointPresetData.Point point(List<WaypointPresetData.Group> groups,
                                                 String pointId) {
        if (groups == null || pointId == null || pointId.isBlank()) {
            return null;
        }
        for (WaypointPresetData.Group group : groups) {
            if (group == null || group.kind() != PresetKind.TREE) {
                continue;
            }
            for (WaypointPresetData.Point point : group.points) {
                if (point != null && pointId.equals(point.id)) {
                    return point;
                }
            }
        }
        return null;
    }
}
