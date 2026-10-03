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
 * The six crate piles on Kuudra's main platform - where a fished-out supply is dropped off, and in
 * the build phase the six things that have to reach 100%.
 *
 * <p><b>They are named after where their crate comes from, not after where they are.</b> The pile
 * called "Triangle" is the one the Triangle crate goes into; it is not near the Triangle camp, it is
 * on the platform with the other five. That naming is the whole point: a party message saying "no
 * tri" is about a missing <i>crate</i>, and the pile named the same way is the one that will be left
 * empty - which is what {@link #feeder} is for.
 *
 * <p>{@link SupplySpot#SQUARE} has no pile of its own (seven spawn spots, six piles), so a "no square"
 * call highlights nothing. That is correct rather than a gap - there is no pile to point at.
 *
 * <p>The Y is deliberately fractional: it is the top of the pile block, which is where the marker
 * wants to sit rather than a block corner.
 */
public enum CratePile {

    SHOP("Shop", new Vec3(-98.5, 78.4, -113.5), SupplySpot.SHOP),
    TRIANGLE("Triangle", new Vec3(-94.5, 78.4, -106.5), SupplySpot.TRIANGLE),
    EQUALS("Equals", new Vec3(-106.5, 78.4, -99.5), SupplySpot.EQUALS),
    SLASH("Slash", new Vec3(-98.5, 78.4, -99.5), SupplySpot.SLASH),
    X_CANNON("X Cannon", new Vec3(-110.5, 78.4, -106.5), SupplySpot.X_CANNON),
    X("X", new Vec3(-106.5, 78.4, -113.5), SupplySpot.X);

    private final String displayName;
    private final Vec3 position;
    private final SupplySpot feeder;

    CratePile(String displayName, Vec3 position, SupplySpot feeder) {
        this.displayName = displayName;
        this.position = position;
        this.feeder = feeder;
    }

    public String displayName() {
        return displayName;
    }

    public Vec3 position() {
        return position;
    }

    /** The spawn spot whose crate belongs in this pile. */
    public SupplySpot feeder() {
        return feeder;
    }

    /** The pile fed by {@code spot}, or {@code null} when that spot has none (Square). */
    public static CratePile forSpot(SupplySpot spot) {
        for (CratePile pile : values()) {
            if (pile.feeder == spot) {
                return pile;
            }
        }
        return null;
    }
}
