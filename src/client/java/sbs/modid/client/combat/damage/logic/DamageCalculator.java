/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.damage.logic;

import sbs.modid.client.combat.damage.model.MobFamily;

/**
 * The SkyBlock melee damage formula (hypixelskyblock.minecraft.wiki, verified 2026-08):
 *
 * <pre>
 * Damage = (5 + WeaponDamage)
 *        × (1 + Strength/100)
 *        × (1 + CritDamage/100)            [critical hits only]
 *        × (1 + Σ additive bonuses / 100)  [Combat level + weapon enchants]
 *        × 100 / (100 + MobDefense)
 * </pre>
 *
 * <p>Additive bonuses: Combat level (+4%/level to 50, +1%/level 51–60), Sharpness
 * (5/10/15/20/30/45/65), the family enchants Smite / Bane of Arthropods / Ender Slayer
 * (5/10/15/20/30/40/50), Cubism (…/40), Impaling (…/30), First Strike (25/level, first hit),
 * Triple-Strike (10/level, first three hits), and the three HP-dependent ones this overlay exists
 * for: <b>Execute</b> (0.2/0.4/0.6/0.8/1.0/1.25 % damage per 1% of the target's <i>missing</i>
 * HP), <b>Prosecute</b> (0.1/0.2/0.3/0.4/0.7/1.0 % per 1% of its <i>current</i> HP, capped at
 * +40%) and <b>Giant Killer</b> (0.1…1.2 % per 1% of HP the target has above yours, capped at
 * 5…65%). Multiplicative buffs the client cannot see (pets, armor passives, potions) are absorbed
 * by the per-mob calibration factor the estimator learns from your real splashes.
 */
public final class DamageCalculator {

    // Per-level additive percentages, index = enchant level (index 0 = not present).
    private static final double[] SHARPNESS = {0, 5, 10, 15, 20, 30, 45, 65};
    private static final double[] SMITE = {0, 5, 10, 15, 20, 30, 40, 50};             // undead
    private static final double[] BANE_OF_ARTHROPODS = {0, 5, 10, 15, 20, 30, 40, 50};
    private static final double[] ENDER_SLAYER = {0, 5, 10, 15, 20, 30, 40, 50};
    private static final double[] CUBISM = {0, 5, 10, 15, 20, 30, 40};
    private static final double[] IMPALING = {0, 5, 10, 15, 20, 30};
    private static final double[] FIRST_STRIKE = {0, 25, 50, 75, 100, 125};
    private static final double[] TRIPLE_STRIKE = {0, 10, 20, 30, 40, 50};
    /** % damage per 1% missing HP. */
    private static final double[] EXECUTE = {0, 0.2, 0.4, 0.6, 0.8, 1.0, 1.25};
    /** % damage per 1% current HP, capped at {@link #PROSECUTE_CAP}. */
    private static final double[] PROSECUTE = {0, 0.1, 0.2, 0.3, 0.4, 0.7, 1.0};
    private static final double PROSECUTE_CAP = 40;
    /** % damage per 1% HP the mob has above the player / the per-level cap. */
    private static final double[] GIANT_KILLER = {0, 0.1, 0.2, 0.3, 0.4, 0.6, 0.9, 1.2};
    private static final double[] GIANT_KILLER_CAP = {0, 5, 10, 15, 20, 30, 45, 65};
    /** % damage per 100 Defense of the target / the per-level cap. */
    private static final double[] TITAN_KILLER = {0, 2, 4, 6, 8, 12, 16, 20};
    private static final double[] TITAN_KILLER_CAP = {0, 6, 12, 18, 24, 40, 60, 80};
    /** One For All: +500% additive, and the weapon can carry no other enchant. */
    private static final double ONE_FOR_ALL = 500;
    /** Bow: 8%/level (community value – the wiki table for Power is incomplete). */
    private static final double POWER_PER_LEVEL = 8;

    /** Everything about the player the formula needs (menu snapshot, weapon-swap adjusted). */
    public record PlayerStats(double strength, double critChance, double critDamage,
                              double attackSpeed, double ferocity, double maxHealth,
                              int combatLevel) {
    }

    /** The target's live state: family, HP right now, and its (table / override) defense. */
    public record MobState(MobFamily family, double currentHp, double maxHp, int defense) {
    }

    /** One evaluated situation. {@code hitsToKill} < 0 means "500+". */
    public record Result(double normal, double crit, double average, double dps, int hitsToKill,
                         double additivePct, double defenseFactor) {
    }

    private DamageCalculator() {
    }

    /**
     * The full estimate against the mob's <b>current</b> HP. {@code hitNumber} is how many times
     * this mob was already hit (0 = First Strike / Triple-Strike still apply); {@code calibration}
     * is the learned real/predicted ratio (1 = none).
     */
    public static Result compute(PlayerStats stats, WeaponInfo weapon, MobState mob,
                                 int hitNumber, double calibration) {
        double normal = hit(stats, weapon, mob, mob.currentHp(), hitNumber, false) * calibration;
        double crit = hit(stats, weapon, mob, mob.currentHp(), hitNumber, true) * calibration;
        double critShare = clamp(stats.critChance(), 0, 100) / 100.0;
        double average = normal * (1 - critShare) + crit * critShare;

        // 2 base swings/s; Bonus Attack Speed (capped 100) shortens the hit cooldown linearly.
        double swingsPerSecond = 2.0 * (1 + clamp(stats.attackSpeed(), 0, 100) / 100.0);
        double ferocityFactor = 1 + Math.max(0, stats.ferocity()) / 100.0;
        double dps = average * swingsPerSecond * ferocityFactor;

        return new Result(normal, crit, average, dps,
                hitsToKill(stats, weapon, mob, hitNumber, calibration, critShare, ferocityFactor),
                additiveSum(stats, weapon, mob, mob.currentHp(), hitNumber),
                100.0 / (100.0 + Math.max(0, mob.defense())));
    }

    /**
     * Swings until the mob dies, re-evaluating Execute / Prosecute / Triple-Strike at the HP each
     * simulated swing meets – the whole point of those enchants is that this is not a division.
     * Returns -1 for "more than 500".
     */
    private static int hitsToKill(PlayerStats stats, WeaponInfo weapon, MobState mob,
                                  int hitNumber, double calibration, double critShare,
                                  double ferocityFactor) {
        double hp = mob.currentHp();
        for (int swing = 0; swing < 500; swing++) {
            double normal = hit(stats, weapon, mob, hp, hitNumber + swing, false);
            double crit = hit(stats, weapon, mob, hp, hitNumber + swing, true);
            double average = (normal * (1 - critShare) + crit * critShare)
                    * calibration * ferocityFactor;
            if (average <= 0) {
                return -1;
            }
            hp -= average;
            if (hp <= 0) {
                return swing + 1;
            }
        }
        return -1;
    }

    /** One raw hit of the formula at the given target HP (no calibration, no ferocity). */
    private static double hit(PlayerStats stats, WeaponInfo weapon, MobState mob,
                              double targetHp, int hitNumber, boolean crit) {
        double base = (5 + weapon.damage()) * (1 + stats.strength() / 100.0);
        if (crit) {
            base *= 1 + stats.critDamage() / 100.0;
        }
        base *= 1 + additiveSum(stats, weapon, mob, targetHp, hitNumber) / 100.0;
        base *= 100.0 / (100.0 + Math.max(0, mob.defense()));
        return base;
    }

    /** The additive percent sum: Combat level + every applicable weapon enchant at {@code targetHp}. */
    private static double additiveSum(PlayerStats stats, WeaponInfo weapon, MobState mob,
                                      double targetHp, int hitNumber) {
        double sum = 0;
        for (double part : additiveParts(stats, weapon, mob, targetHp, hitNumber).values()) {
            sum += part;
        }
        return sum;
    }

    /**
     * Every additive term by name, in application order – the sum is the additive multiplier, and
     * the breakdown is what the debug log prints so a wrong estimate can be traced to one term
     * instead of guessed at.
     */
    public static java.util.LinkedHashMap<String, Double> additiveParts(
            PlayerStats stats, WeaponInfo weapon, MobState mob, double targetHp, int hitNumber) {
        java.util.LinkedHashMap<String, Double> parts = new java.util.LinkedHashMap<>();
        put(parts, "Combat " + stats.combatLevel(), combatBonus(stats.combatLevel()));
        if (weapon.enchant("one_for_all") > 0) {
            // One For All replaces every other enchant, so nothing else can contribute.
            put(parts, "One For All", ONE_FOR_ALL);
            return parts;
        }
        put(parts, "Sharpness", level(SHARPNESS, weapon.enchant("sharpness")));
        put(parts, "Power", weapon.enchant("power") * POWER_PER_LEVEL);
        switch (mob.family()) {
            case UNDEAD -> put(parts, "Smite", level(SMITE, weapon.enchant("smite")));
            case ARTHROPOD -> put(parts, "Bane of Arthropods",
                    level(BANE_OF_ARTHROPODS, weapon.enchant("bane_of_arthropods")));
            case ENDER -> put(parts, "Ender Slayer", level(ENDER_SLAYER, weapon.enchant("ender_slayer")));
            case CUBIC -> put(parts, "Cubism", level(CUBISM, weapon.enchant("cubism")));
            case AQUATIC -> put(parts, "Impaling", level(IMPALING, weapon.enchant("impaling")));
            case NONE -> { }
        }
        if (hitNumber == 0) {
            put(parts, "First Strike", level(FIRST_STRIKE, weapon.enchant("first_strike")));
        }
        if (hitNumber < 3) {
            put(parts, "Triple-Strike", level(TRIPLE_STRIKE, weapon.enchant("triple_strike")));
        }
        if (mob.maxHp() > 0) {
            double currentPct = clamp(targetHp / mob.maxHp() * 100.0, 0, 100);
            put(parts, "Execute",
                    EXECUTE[clampLevel(EXECUTE, weapon.enchant("execute"))] * (100 - currentPct));
            put(parts, "Prosecute", Math.min(PROSECUTE_CAP,
                    PROSECUTE[clampLevel(PROSECUTE, weapon.enchant("prosecute"))] * currentPct));
        }
        int giantKiller = clampLevel(GIANT_KILLER, weapon.enchant("giant_killer"));
        if (giantKiller > 0 && stats.maxHealth() > 0 && mob.maxHp() > stats.maxHealth()) {
            double extraPct = (mob.maxHp() - stats.maxHealth()) / stats.maxHealth() * 100.0;
            put(parts, "Giant Killer",
                    Math.min(GIANT_KILLER_CAP[giantKiller], GIANT_KILLER[giantKiller] * extraPct));
        }
        int titanKiller = clampLevel(TITAN_KILLER, weapon.enchant("titan_killer"));
        if (titanKiller > 0 && mob.defense() > 0) {
            put(parts, "Titan Killer", Math.min(TITAN_KILLER_CAP[titanKiller],
                    TITAN_KILLER[titanKiller] * mob.defense() / 100.0));
        }
        return parts;
    }

    private static void put(java.util.Map<String, Double> parts, String name, double value) {
        if (value != 0) {
            parts.put(name, value);
        }
    }

    /** The Warrior skill bonus: +4% per Combat level up to 50, +1% each for 51–60 (max +210%). */
    public static double combatBonus(int combatLevel) {
        int capped = clampInt(combatLevel, 0, 60);
        return Math.min(capped, 50) * 4 + Math.max(0, capped - 50);
    }

    private static double level(double[] table, int level) {
        return table[clampLevel(table, level)];
    }

    /** Levels above the table (a new Hypixel tier) fall back to the highest known entry. */
    private static int clampLevel(double[] table, int level) {
        return clampInt(level, 0, table.length - 1);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
