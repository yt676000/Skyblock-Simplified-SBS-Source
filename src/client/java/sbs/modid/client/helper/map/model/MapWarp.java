/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.map.model;

import net.minecraft.world.phys.Vec3;

/**
 * One fast-travel destination on an island map, <b>with the coordinates it drops you at</b>.
 *
 * <p>The arrival point is the whole reason this exists as its own type rather than reusing
 * {@link sbs.modid.client.helper.warp.WarpCatalog.Warp}: "which warp is nearest to the Bank" is a
 * distance question, and the warp menu's catalog only knows the command to run. A warp with no
 * arrival coordinates cannot be compared against, so it is never chosen as a nearest warp - it can
 * still be listed, it just does not compete.
 *
 * <p>A plain mutable POJO because Gson builds it straight out of the island's JSON file.
 */
public final class MapWarp {

    /** Short id the locations reference in their {@code warp} field ("museum"). */
    public String id = "";

    /** The command actually run ("/warp museum"). Kept verbatim so an odd one (/visit prtl) fits. */
    public String command = "";

    /** What the player sees ("Museum"). Falls back to the id when the file omits it. */
    public String label = "";

    /** Where the warp puts you. */
    public int x;
    public int y;
    public int z;

    /** Optional aside shown in the tooltip ("Community Shop"). */
    public String note = "";

    /** Gson needs a no-arg constructor. */
    public MapWarp() {
    }

    /** The label to show, falling back to the id and finally the command. */
    public String displayLabel() {
        if (label != null && !label.isBlank()) {
            return label;
        }
        if (id != null && !id.isBlank()) {
            return id;
        }
        return command == null ? "" : command;
    }

    /**
     * Whether this warp has a usable arrival point. A file may list a warp purely so the player can
     * see it exists; without coordinates it cannot take part in the nearest-warp comparison, and
     * silently treating a missing coordinate as {@code (0, 0, 0)} would make it look nearest to
     * everything on an island whose spawn is far from the origin.
     */
    public boolean hasPosition() {
        return !(x == 0 && y == 0 && z == 0);
    }

    public Vec3 position() {
        return new Vec3(x, y, z);
    }

    /** Horizontal distance from this warp's arrival point to a spot on the same island. */
    public double horizontalDistanceTo(double targetX, double targetZ) {
        double dx = x - targetX;
        double dz = z - targetZ;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
