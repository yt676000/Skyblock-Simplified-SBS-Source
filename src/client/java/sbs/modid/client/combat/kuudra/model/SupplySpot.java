/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.kuudra.model;

import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * The seven places a supply crate can surface during the supplies phase, with the names the community
 * calls them by.
 *
 * <p><b>Why these need names at all.</b> A Kuudra team splits up before the crates exist: each player
 * parks on one spot, and the two spots nobody covered are the ones that will be called out. The names
 * are shape names for what the platform looks like there ("X", "Slash", "Equals", "Triangle") plus
 * three landmarks ("Shop", "Square", "X Cannon"), and they are what gets typed into party chat -
 * "no tri", "missing xc". {@link #match} is the other half of that: it turns whatever somebody typed
 * back into one of these.
 *
 * <p>The coordinates are the middle of each camping spot, which is what the crate spawns next to.
 * They are fixed arena geometry - Kuudra's Hollow is the same instance every run - so they live in
 * code rather than in a config file.
 */
public enum SupplySpot {

    X(1, "X", new Vec3(-142.5, 77, -151), "x"),
    X_CANNON(2, "X Cannon", new Vec3(-143, 76, -125), "xcannon", "xc"),
    SQUARE(3, "Square", new Vec3(-143, 76, -80), "square", "sq"),
    SLASH(4, "Slash", new Vec3(-113.5, 77, -68.5), "slash"),
    EQUALS(5, "Equals", new Vec3(-65.5, 76, -87.5), "equals", "eq"),
    TRIANGLE(6, "Triangle", new Vec3(-67.5, 77, -122.5), "triangle", "tri"),
    SHOP(7, "Shop", new Vec3(-81, 76, -143), "shop");

    /**
     * The call number, 1-7. Only used to tie a spot to {@linkplain CratePile the pile its crate
     * belongs in} - the piles are labelled by which spot feeds them.
     */
    private final int call;

    private final String displayName;
    private final Vec3 position;

    /**
     * What somebody might type for this spot. The full name is always included; the rest are the
     * short forms people actually use mid-run, when there is no time to type "triangle".
     */
    private final String[] aliases;

    SupplySpot(int call, String displayName, Vec3 position, String... aliases) {
        this.call = call;
        this.displayName = displayName;
        this.position = position;
        this.aliases = aliases;
    }

    public int call() {
        return call;
    }

    public String displayName() {
        return displayName;
    }

    public Vec3 position() {
        return position;
    }

    /** The spot with this call number, or {@code null} - {@code 0} means "no spot", not an error. */
    public static SupplySpot byCall(int call) {
        for (SupplySpot spot : values()) {
            if (spot.call == call) {
                return spot;
            }
        }
        return null;
    }

    /**
     * The spot named by a single already-normalised word, or {@code null}.
     *
     * <p>Exact matches only, deliberately. This is fed words out of other people's party messages,
     * and a fuzzy match there would have "sq" inside "squad" firing a call-out - the whole feature
     * depends on never crying wolf.
     */
    public static SupplySpot match(String word) {
        String needle = word.toLowerCase(Locale.ROOT);
        for (SupplySpot spot : values()) {
            for (String alias : spot.aliases) {
                if (alias.equals(needle)) {
                    return spot;
                }
            }
        }
        return null;
    }
}
