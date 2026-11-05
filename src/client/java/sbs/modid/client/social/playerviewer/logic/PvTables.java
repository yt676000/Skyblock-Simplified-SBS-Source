/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.playerviewer.logic;

import java.util.List;
import java.util.Map;

/**
 * Static SkyBlock tables the viewer's calculations use, bundled because they change only with a
 * game update. Values are the ones the old SBS server used (party_server.py / api_server.py).
 */
public final class PvTables {

    private PvTables() {
    }

    /** Cumulative skill XP; index = level (0..60). */
    public static final long[] SKILL_XP = {0, 50, 175, 375, 675, 1175, 1925, 2925, 4425, 6425, 9925, 14925,
            22425, 32425, 47425, 67425, 97425, 147425, 222425, 322425, 522425,
            822425, 1222425, 1722425, 2322425, 3022425, 3822425, 4722425,
            5722425, 6822425, 8022425, 9322425, 10722425, 12222425, 13822425,
            15522425, 17322425, 19222425, 21222425, 23322425, 25522425,
            27822425, 30222425, 32722425, 35322425, 38072425, 40972425,
            44072425, 47472425, 51172425, 55172425, 59472425, 64072425,
            68972425, 74172425, 79672425, 85472425, 91572425, 97972425,
            104672425, 111672425};

    /** Cumulative Catacombs / class XP; index = level (0..50). */
    public static final long[] CATA_XP = {0, 50, 125, 235, 395, 625, 955, 1425, 2095, 3045, 4385, 6275, 8940,
            12700, 17960, 25340, 35640, 50040, 70040, 97640, 135640, 188140,
            259640, 356640, 488640, 668640, 911640, 1239640, 1684640, 2284640,
            3084640, 4149640, 5559640, 7459640, 9959640, 13259640, 17559640,
            23159640, 30359640, 39559640, 51559640, 66559640, 85559640,
            109559640, 139559640, 177559640, 225559640, 285559640, 360559640,
            453559640, 569809640};

    public static final long[] SLAYER_XP = {0, 5, 15, 200, 1000, 5000, 20000, 100000, 400000, 1000000};
    public static final long[] SLAYER_VAMPIRE_XP = {0, 20, 75, 240, 840, 2400};
    public static final long[] HOTM_XP = {0, 3000, 12000, 37000, 97000, 197000, 347000, 557000, 847000, 1247000};

    public static final List<String> PET_TIERS = List.of("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC");
    public static final List<String> KUUDRA_TIERS = List.of("none", "hot", "burning", "fiery", "infernal");
    public static final List<String> CRYSTALS = List.of("amethyst_crystal", "amber_crystal", "topaz_crystal",
            "jade_crystal", "sapphire_crystal");

    /** Per-level pet XP; a tier starts at its {@link #PET_XP_OFFSET}. */
    public static final long[] PET_XP_DELTAS = {
            100, 110, 120, 130, 145, 160, 175, 190, 210, 230, 250, 275, 300, 330, 360,
            400, 440, 490, 540, 600, 660, 730, 800, 880, 960, 1050, 1150, 1260, 1380,
            1510, 1650, 1800, 1960, 2130, 2310, 2500, 2700, 2920, 3160, 3420, 3700,
            4000, 4350, 4750, 5200, 5700, 6300, 7000, 7800, 8700, 9700, 10800, 12000,
            13300, 14700, 16200, 17800, 19500, 21300, 23200, 25200, 27400, 29800,
            32400, 35200, 38200, 41400, 44800, 48400, 52200, 56200, 60400, 64800,
            69400, 74200, 79200, 84700, 90700, 97200, 104200, 111700, 119700, 128200,
            137200, 146700, 156700, 167700, 179700, 192700, 206700, 221700, 237700,
            254700, 272700, 291700, 311700, 333700, 357700, 383700, 411700, 441700,
            476700, 516700, 561700, 611700, 666700, 726700, 791700, 861700, 936700,
            1016700, 1101700, 1191700, 1286700, 1386700, 1496700, 1616700, 1746700,
            1886700};

    public static final Map<String, Integer> PET_XP_OFFSET = Map.of("COMMON", 0, "UNCOMMON", 6, "RARE", 11,
            "EPIC", 16, "LEGENDARY", 20, "MYTHIC", 20);

    /** Magical Power per accessory rarity; SUPREME is the legacy name of MYTHIC. */
    public static final Map<String, Integer> MP_PER_RARITY = Map.of("COMMON", 3, "UNCOMMON", 5, "RARE", 8,
            "EPIC", 12, "LEGENDARY", 16, "MYTHIC", 22, "SUPREME", 22, "SPECIAL", 3, "VERY_SPECIAL", 5);

    public static final List<String> RARITY_LADDER = List.of("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC");
    public static final List<String> POWDERS = List.of("mithril", "gemstone", "glacite");
    public static final List<String> TROPHY_TIERS = List.of("bronze", "silver", "gold", "diamond");

    /** Crimson Isle dojo test key suffix -> display name, in the order the page lists them. */
    public static final List<String[]> DOJO_TESTS = List.of(
            new String[]{"mob_kb", "Force"}, new String[]{"wall_jump", "Stamina"},
            new String[]{"archer", "Mastery"}, new String[]{"sword_swap", "Discipline"},
            new String[]{"snake", "Swiftness"}, new String[]{"fireball", "Control"},
            new String[]{"lock_head", "Tenacity"});

    /** The Mythological Ritual mobs, as bestiary key fragments. */
    public static final List<String> MYTHOS_MOBS = List.of("minotaur", "gaia_construct", "siamese_lynx",
            "minos_hunter", "minos_champion", "minos_inquisitor");

    public static final int DUNGEON_FLOORS = 7;
}
