/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.slayer.logic;

/**
 * Expected bosses for one RNG drop. Pure math, unit-tested. Formulas from
 * hypixelskyblock.minecraft.wiki (Magic_Find, RNG_Meter), read 2026-11-22:
 * <ul>
 *   <li>chance = base × (1 + MF/100), MF capped at 500;</li>
 *   <li>a selected drop's chance × (1 + min(2 × stored/required, 2)), and at stored ≥ required the
 *       drop is guaranteed. That slayer drops combine both is marked "[Confirm]" on the wiki.</li>
 * </ul>
 */
public final class RngCalculator {

    public static final int MF_CAP = 500;
    /** Stops the walk for drops so rare the meter is the only realistic way (dyes). */
    private static final int MAX_STEPS = 5_000_000;

    private RngCalculator() {
    }

    /** Chance per boss in [0, 1], before the meter. */
    public static double chance(double basePercent, double magicFind) {
        double mf = Math.max(0, Math.min(MF_CAP, magicFind));
        return Math.min(1.0, basePercent / 100.0 * (1 + mf / 100.0));
    }

    /** The meter's multiplier on the selected drop at {@code stored} of {@code required} XP. */
    public static double meterMultiplier(double stored, double required) {
        if (required <= 0 || stored <= 0) {
            return 1.0;
        }
        return 1 + Math.min(2 * stored / required, 2);
    }

    /** Average bosses from MF alone: 1 / chance. Infinite when the chance is zero. */
    public static double expectedBosses(double basePercent, double magicFind) {
        double p = chance(basePercent, magicFind);
        return p <= 0 ? Double.POSITIVE_INFINITY : 1 / p;
    }

    /**
     * Average bosses with the drop selected on the meter: each boss adds {@code gainPerBoss} XP,
     * raising the chance, and the boss that brings the meter to {@code required} guarantees it.
     * Falls back to {@link #expectedBosses(double, double)} when the meter is unknown.
     */
    public static double expectedBossesWithMeter(double basePercent, double magicFind,
                                                 double stored, double required, double gainPerBoss) {
        if (required <= 0 || gainPerBoss <= 0 || stored < 0) {
            return expectedBosses(basePercent, magicFind);
        }
        double p0 = chance(basePercent, magicFind);
        double expected = 0;
        double survive = 1;   // chance no drop yet
        double meter = stored;
        for (int k = 1; k <= MAX_STEPS; k++) {
            if (meter >= required) {
                return expected + k * survive;   // guaranteed this boss
            }
            double p = Math.min(1.0, p0 * meterMultiplier(meter, required));
            expected += k * survive * p;
            survive *= 1 - p;
            if (survive < 1e-12) {
                return expected;
            }
            meter += gainPerBoss;
        }
        return expected;
    }
}
