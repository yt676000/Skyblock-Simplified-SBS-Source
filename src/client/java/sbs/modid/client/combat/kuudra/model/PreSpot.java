/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.kuudra.model;

import net.minecraft.world.phys.Vec3;

/**
 * The four spots a player camps ("pres") before the supplies phase starts, each with the second spot
 * that player is expected to sweep afterwards.
 *
 * <p><b>The difference between this and {@link SupplySpot} is who is standing there.</b> All seven
 * supply spots are places a crate can surface; these four are the places a <i>player</i> waits. Which
 * one you are on decides two things: what the mod calls out when nothing spawns for you ("no tri"),
 * and which second crate is yours to go and get once your own is in.
 *
 * <p>{@link #EQUALS} has no second spot. Four players cover seven crates, so one of them is on a
 * single-crate rotation - that is not a missing entry.
 *
 * <p>{@link #radius} is how close to the primary spot you have to be to count as camping it. X is
 * given a much wider one on purpose: its camp is a long ledge and players sit anywhere along it,
 * while the other three are a tight platform each.
 */
public enum PreSpot {

    X(SupplySpot.X, SupplySpot.X_CANNON, 30.0),
    SLASH(SupplySpot.SLASH, SupplySpot.SQUARE, 15.0),
    EQUALS(SupplySpot.EQUALS, null, 15.0),
    TRIANGLE(SupplySpot.TRIANGLE, SupplySpot.SHOP, 15.0);

    private final SupplySpot primary;
    private final SupplySpot secondary;
    private final double radius;

    PreSpot(SupplySpot primary, SupplySpot secondary, double radius) {
        this.primary = primary;
        this.secondary = secondary;
        this.radius = radius;
    }

    /** The crate this camp exists to catch. */
    public SupplySpot primary() {
        return primary;
    }

    /** The second crate this camp sweeps up afterwards, or {@code null} for the single-crate camp. */
    public SupplySpot secondary() {
        return secondary;
    }

    public double radius() {
        return radius;
    }

    public String displayName() {
        return primary.displayName();
    }

    /**
     * The camp {@code pos} is standing on, or {@code null} when it is on none of them.
     *
     * <p>Compared on X/Z only. The camps are ledges at slightly different heights and players stand
     * on blocks, on slabs and on each other's heads; the height they picked says nothing about which
     * camp they are on, and folding it into the distance only ever produced misses.
     */
    public static PreSpot at(Vec3 pos) {
        for (PreSpot spot : values()) {
            double dx = pos.x - spot.primary.position().x;
            double dz = pos.z - spot.primary.position().z;
            if (dx * dx + dz * dz <= spot.radius * spot.radius) {
                return spot;
            }
        }
        return null;
    }
}
