/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.kuudra.model;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * A patch of Kuudra's Hollow, and the pearl markers that are worth showing while you stand in it.
 *
 * <p><b>Areas exist so the screen is not covered in pearls.</b> A full setup is twenty-odd throws,
 * of which at most two or three are throwable from where you happen to be standing. Each area is a
 * rectangle on X/Z around one camping spot, and only its own markers are drawn - walk to the next
 * camp and the markers swap over.
 *
 * <p><b>The two invert flags are the whole trick behind "it works even though I moved".</b> A throw
 * recorded from block {@code S} and taken from block {@code S + d} lands {@code d} away from where it
 * should. Shifting the aim point by {@code d} cancels that out, and whether it has to be {@code +d} or
 * {@code -d} depends on which way the platform faces relative to the target - which is a property of
 * the area, not of the individual throw. {@code null} means "do not compensate on this axis": some
 * camps are ledges where stepping sideways changes nothing about the throw, and compensating there
 * makes the marker wander for no reason.
 *
 * <p>Plain mutable fields with a no-arg constructor: read and written straight by Gson.
 */
public final class PearlArea {

    /** Player-facing name. Free text - the module never parses it. */
    public String name = "";

    /** One corner of the rectangle, on X/Z. Y is not part of an area. */
    public double x1;
    public double z1;

    /** The opposite corner. */
    public double x2;
    public double z2;

    /**
     * Compensation on the north/south axis: {@code FALSE} shifts the aim point the same way you moved,
     * {@code TRUE} shifts it the opposite way, {@code null} leaves the axis alone.
     */
    public Boolean invertNorthSouth;

    /** The same for the east/west axis. */
    public Boolean invertEastWest;

    /** Whether this area's markers are drawn at all - a per-area mute, without deleting anything. */
    public boolean visible = true;

    public List<PearlPoint> points = new ArrayList<>();

    /** Gson needs a no-arg constructor. */
    public PearlArea() {
    }

    /** Whether {@code pos} is inside this area. Corners in either order, X/Z only. */
    public boolean contains(Vec3 pos) {
        return pos.x >= Math.min(x1, x2) && pos.x <= Math.max(x1, x2)
                && pos.z >= Math.min(z1, z2) && pos.z <= Math.max(z1, z2);
    }

    /**
     * Where {@code point} should actually be drawn for a player standing at {@code playerPos}.
     *
     * <p>Returns the recorded aim point unchanged when the throw has no stand block or when both axes
     * are switched off - the compensation is opt-in per area, and a marker that was recorded as
     * static must stay exactly where it was put.
     */
    public Vec3 resolve(PearlPoint point, Vec3 playerPos) {
        if (!point.hasStand() || (invertNorthSouth == null && invertEastWest == null)) {
            return new Vec3(point.x, point.y, point.z);
        }
        double shiftX = 0;
        double shiftZ = 0;
        if (invertEastWest != null) {
            shiftX = (playerPos.x - point.standX) * (invertEastWest ? -1 : 1);
        }
        if (invertNorthSouth != null) {
            shiftZ = (playerPos.z - point.standZ) * (invertNorthSouth ? -1 : 1);
        }
        return new Vec3(point.x + shiftX, point.y, point.z + shiftZ);
    }
}
