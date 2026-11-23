/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.slayer.model;

import sbs.modid.client.combat.carry.model.SlayerBoss;

import java.util.ArrayList;
import java.util.List;

/**
 * The RNG-meter drops of each slayer with their base chance at the boss's highest tier, from the
 * boss pages on hypixelskyblock.minecraft.wiki (read 2026-11-22). See
 * {@code docs/knowledge/slayer-rng-calculator.md} for the sources and what is unverified.
 */
public final class SlayerRngDrops {

    /** One drop: base chance in percent at {@code tier}, the highest tier it is listed for. */
    public record Drop(SlayerBoss boss, String name, int tier, double chancePercent) {
    }

    private static final List<Drop> ALL = List.of(
            new Drop(SlayerBoss.REVENANT, "Revenant Shard", 5, 1.0),
            new Drop(SlayerBoss.REVENANT, "Snake Rune", 5, 0.15),
            new Drop(SlayerBoss.REVENANT, "Warden Heart", 5, 0.02),
            new Drop(SlayerBoss.REVENANT, "Matcha Dye", 5, 0.001),
            new Drop(SlayerBoss.TARANTULA, "Toxic Arrow Poison", 5, 5.0),
            new Drop(SlayerBoss.TARANTULA, "Tarantula Talisman", 5, 0.25),
            new Drop(SlayerBoss.TARANTULA, "Vial of Venom", 5, 0.1),
            new Drop(SlayerBoss.TARANTULA, "Primordial Eye", 5, 0.02),
            new Drop(SlayerBoss.SVEN, "Hamster Wheel", 4, 5.0),
            new Drop(SlayerBoss.SVEN, "Spirit Rune", 4, 2.5),
            new Drop(SlayerBoss.SVEN, "Furball", 4, 1.0),
            new Drop(SlayerBoss.SVEN, "Red Claw Egg", 4, 0.2),
            new Drop(SlayerBoss.SVEN, "Overflux Capacitor", 4, 0.075),
            new Drop(SlayerBoss.SVEN, "Celeste Dye", 4, 0.0004),
            new Drop(SlayerBoss.VOIDGLOOM, "Twilight Arrow Poison", 4, 5.0),
            new Drop(SlayerBoss.VOIDGLOOM, "Null Atom", 4, 5.0),
            new Drop(SlayerBoss.VOIDGLOOM, "Judgement Core", 4, 0.075),
            new Drop(SlayerBoss.VOIDGLOOM, "Exceedingly Rare Ender Artifact Upgrade", 4, 0.05),
            new Drop(SlayerBoss.VOIDGLOOM, "Byzantium Dye", 4, 0.0004),
            new Drop(SlayerBoss.INFERNO, "Scorched Power Crystal", 4, 2.5),
            new Drop(SlayerBoss.INFERNO, "Archfiend Dice", 4, 1.0),
            new Drop(SlayerBoss.INFERNO, "Fiery Burst Rune", 4, 0.25),
            new Drop(SlayerBoss.INFERNO, "High Class Archfiend Dice", 4, 0.25),
            new Drop(SlayerBoss.INFERNO, "Flame Dye", 4, 0.0004),
            new Drop(SlayerBoss.BLOODFIEND, "McGrubber's Burger", 5, 2.5),
            new Drop(SlayerBoss.BLOODFIEND, "Enchanted Book Bundle (The One)", 5, 1.75),
            new Drop(SlayerBoss.BLOODFIEND, "Unfanged Vampire Part", 5, 1.0),
            new Drop(SlayerBoss.BLOODFIEND, "Sangria Dye", 5, 0.02));

    private SlayerRngDrops() {
    }

    public static List<Drop> of(SlayerBoss boss) {
        List<Drop> out = new ArrayList<>();
        for (Drop drop : ALL) {
            if (drop.boss() == boss) {
                out.add(drop);
            }
        }
        return out;
    }

    /** The drop of {@code boss} named {@code name}, else that slayer's first (most common) one. */
    public static Drop find(SlayerBoss boss, String name) {
        List<Drop> drops = of(boss);
        for (Drop drop : drops) {
            if (drop.name().equals(name)) {
                return drop;
            }
        }
        return drops.isEmpty() ? null : drops.get(0);
    }
}
