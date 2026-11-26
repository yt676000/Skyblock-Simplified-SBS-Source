/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.fishing.logic;

import java.util.List;

/**
 * Notices when the hotspot you are fishing in despawns, so you stop casting into plain water.
 *
 * <p>Hotspots vanish a few minutes after they appear, and nothing in game says so - the buff simply
 * stops applying. This tracks the hotspot the bobber sits in (its {@code HOTSPOT} label within
 * {@link #CLAIM_RADIUS} horizontally) and reports once when that label is no longer there although the
 * player is still close enough for it to be loaded. Walking away, or the label merely leaving view
 * distance, forgets the hotspot silently.
 *
 * <p>Pure state, no game access: {@link #onScan} gets the label positions, the bobber and the player,
 * which keeps it testable. Unverified in game: the claim radius against real hotspot sizes.
 */
public final class HotspotGoneWatch {

    /** How close (blocks, horizontal) the bobber must be to a label for that hotspot to be "yours". */
    static final double CLAIM_RADIUS = 6.0;

    /** Beyond this distance from the player a missing label may just be unloaded - no alert. */
    static final double LOADED_RADIUS = 24.0;

    /** A position on the x/z plane; y does not matter for either test. */
    public record Pos(double x, double z) {
        double distSq(Pos o) {
            double dx = x - o.x;
            double dz = z - o.z;
            return dx * dx + dz * dz;
        }
    }

    private Pos tracked;

    /**
     * One scan. {@code bobber} is null while the line is in.
     *
     * @return true exactly once, on the scan where the tracked hotspot is found gone
     */
    public boolean onScan(List<Pos> labels, Pos bobber, Pos player) {
        if (tracked != null) {
            boolean stillThere = false;
            for (Pos label : labels) {
                if (label.distSq(tracked) < 1.0) {
                    stillThere = true;
                    break;
                }
            }
            if (!stillThere) {
                boolean nearby = player != null && player.distSq(tracked) <= LOADED_RADIUS * LOADED_RADIUS;
                tracked = null;
                return nearby;
            }
            if (player != null && player.distSq(tracked) > LOADED_RADIUS * LOADED_RADIUS) {
                tracked = null;
            }
        }
        if (bobber != null) {
            Pos nearest = null;
            double best = CLAIM_RADIUS * CLAIM_RADIUS;
            for (Pos label : labels) {
                double d = label.distSq(bobber);
                if (d <= best) {
                    best = d;
                    nearest = label;
                }
            }
            if (nearest != null) {
                tracked = nearest;
            }
        }
        return false;
    }

    public void reset() {
        tracked = null;
    }
}
