/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.damage.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Combat stats per SkyBlock mob for the Damage Overlay: max HP (fallback) and <b>Defense</b>, keyed
 * by the floating-nametag name plus the {@code [LvN]} level, because most mobs exist in several
 * level variants with very different stats.
 *
 * <p><b>Live data beats the table.</b> The mob's current and max HP are read off its nametag
 * ({@code "cur/max❤"}) whenever present, so the HP here only fills in when a nametag omits the max.
 * Defense is the one stat the client can never see – and since the wiki stopped publishing per-mob
 * Defense values, the entries here default to 0 and the overlay corrects itself two ways instead:
 * the user's {@code mobDefenseOverrides} CSV, and the automatic calibration against your own
 * attributed splashes (predicted vs. real damage), which absorbs defense <i>and</i> every
 * unmodelled multiplier at once.
 *
 * <p>HP values below were verified against hypixelskyblock.minecraft.wiki (2026-08). Extending the
 * table is one {@code mob(...)} line per variant.
 */
public final class MobCombatCatalog {

    /** One mob variant: its nametag level, max HP (0 = unknown), Defense, and an optional family override. */
    public record Variant(int level, long hp, int defense, MobFamily family) {
    }

    /** Normalized name -> its level variants (unordered; lookup picks the closest level). */
    private static final Map<String, List<Variant>> BY_NAME = new HashMap<>();

    static {
        register();
    }

    private MobCombatCatalog() {
    }

    /** "Minos Inquisitor" / "minos  inquisitor" -> "minos inquisitor" (the lookup key). */
    public static String key(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }

    /**
     * The variant of {@code name} closest to the nametag {@code level}, or {@code null} when the
     * mob is not in the table. An exact level match wins; otherwise the nearest level, so a variant
     * added by Hypixel after this table was written still resolves to something sensible.
     */
    public static Variant find(String name, int level) {
        List<Variant> variants = BY_NAME.get(key(name));
        if (variants == null || variants.isEmpty()) {
            return null;
        }
        Variant best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Variant variant : variants) {
            int dist = Math.abs(variant.level() - level);
            if (dist < bestDist) {
                bestDist = dist;
                best = variant;
            }
        }
        return best;
    }

    private static void mob(String name, int level, long hp, int defense) {
        mob(name, level, hp, defense, null);
    }

    private static void mob(String name, int level, long hp, int defense, MobFamily family) {
        BY_NAME.computeIfAbsent(key(name), k -> new ArrayList<>())
                .add(new Variant(level, hp, defense, family));
    }

    private static void register() {
        // ---- The End (wiki-verified HP) -------------------------------------------------------
        mob("Enderman", 42, 4_500, 0);
        mob("Enderman", 45, 6_000, 0);
        mob("Enderman", 50, 9_000, 0);
        mob("Zealot", 55, 13_000, 0);
        mob("Special Zealot", 55, 2_000, 0);
        // Voidlings are Ender mobs whatever entity Hypixel dresses them in.
        mob("Voidling Fanatic", 85, 0, 0, MobFamily.ENDER);
        mob("Voidling Extremist", 100, 0, 0, MobFamily.ENDER);
        mob("Watcher", 55, 0, 0, MobFamily.ENDER);
        mob("Obsidian Defender", 55, 0, 0, MobFamily.ENDER);

        // ---- Diana / Mythological creatures (wiki-verified HP, 2026 variant system) -----------
        mob("Minos Hunter", 15, 4_000, 0);
        mob("Minos Hunter", 25, 15_000, 0);
        mob("Minos Hunter", 60, 100_000, 0);
        mob("Minos Hunter", 80, 350_000, 0);
        mob("Minos Hunter", 125, 1_000_000, 0);
        mob("Minos Hunter", 200, 1_750_000, 0);
        mob("Siamese Lynx", 15, 2_500, 0);
        mob("Siamese Lynx", 25, 12_500, 0);
        mob("Siamese Lynx", 55, 75_000, 0);
        mob("Siamese Lynx", 85, 250_000, 0);
        mob("Siamese Lynx", 155, 750_000, 0);
        mob("Siamese Lynx", 200, 1_250_000, 0);
        mob("Minotaur", 45, 1_500_000, 0);
        mob("Minotaur", 120, 8_000_000, 0);
        mob("Minotaur", 210, 15_500_000, 0);
        mob("Minos Champion", 175, 2_000_000, 0);
        mob("Minos Champion", 310, 12_500_000, 0);
        mob("Minos Champion", 550, 25_000_000, 0);
        mob("Gaia Construct", 85, 175_000, 0);
        mob("Gaia Construct", 140, 650_000, 0);
        mob("Gaia Construct", 175, 1_500_000, 0);
        mob("Gaia Construct", 325, 3_500_000, 0);

        // ---- Slayer bosses (wiki-verified HP; defense unpublished, calibration covers it) -----
        mob("Revenant Horror", 10, 500, 0, MobFamily.UNDEAD);
        mob("Revenant Horror", 70, 20_000, 0, MobFamily.UNDEAD);
        mob("Revenant Horror", 310, 400_000, 0, MobFamily.UNDEAD);
        mob("Revenant Horror", 610, 1_500_000, 0, MobFamily.UNDEAD);
        mob("Atoned Horror", 1580, 10_000_000, 0, MobFamily.UNDEAD);
        mob("Voidgloom Seraph", 200, 0, 0, MobFamily.ENDER);
        mob("Riftstalker Bloodfiend", 200, 0, 0, MobFamily.UNDEAD);
    }
}
