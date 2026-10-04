/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.model;

import sbs.modid.client.helper.rift.model.Certainty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which vanilla blocks are Mithril, Umber and Tungsten ore, what each one yields per unit of
 * mining effort, and where each ore may be looked for at all. Pure: no game state, so the whole
 * ranking and the zone gate are unit-tested ({@code EffectiveBlockTableTest}).
 *
 * <p><b>The score is drops per 1,000 block strength.</b> At a constant mining speed the time to
 * break a block is proportional to its strength, so drops divided by strength is yield per second
 * of mining. Ranking is only ever done <i>within</i> one ore: Mithril and Umber are never mined
 * for the same purpose, so a tier that compared them would answer a question nobody asks.
 *
 * <p><b>Insta-mining breaks the strength model.</b> A player fast enough to break the soft Mithril
 * blocks (gray wool, cyan terracotta) in one tick is limited by the tick, not by strength, and
 * then those blocks beat everything else per second. That is not detected from mining speed; the
 * player says so with the "I insta-mine soft mithril" setting, and {@link #tiers} puts the soft
 * blocks on top.
 *
 * <p><b>Every number here is {@link Certainty#WIKI}</b> (hypixelskyblock.minecraft.wiki, the
 * Mithril, Umber and Tungsten pages, read 2026-10-04) until it has been watched in game.
 */
public final class EffectiveBlockTable {

    /** The three ores the overlay knows. */
    public enum Ore {
        MITHRIL("Mithril"),
        UMBER("Umber"),
        TUNGSTEN("Tungsten");

        private final String displayName;

        Ore(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    /** Where a block sits among the enabled blocks of its own ore. */
    public enum Tier { BEST, MIDDLE, LOW }

    /**
     * One ore block.
     *
     * @param blockId   the vanilla registry path, without the {@code minecraft:} namespace
     * @param drops     ore (and, for Mithril, powder) per block broken
     * @param strength  Hypixel's block strength
     * @param soft      one of the two Mithril blocks that can be insta-mined
     * @param halfBlock a slab or stairs worth half a block - off unless the player turns them on
     */
    public record Entry(String blockId, Ore ore, double drops, int strength, boolean soft,
                        boolean halfBlock, Certainty certainty) {

        /** Drops per 1,000 strength: yield per unit of mining time at a constant speed. */
        public double score() {
            return drops * 1000.0 / strength;
        }
    }

    /** The island the Mithril, Umber and Tungsten zones belong to, as the location reports it. */
    public static final String DWARVEN_MINES = "Dwarven Mines";
    public static final String CRYSTAL_HOLLOWS = "Crystal Hollows";
    public static final String GLACITE_MINESHAFTS = "Glacite Mineshafts";
    /** The tab list's own word for a Mineshaft instance; resolves to {@link #GLACITE_MINESHAFTS}. */
    public static final String MINESHAFT = "Mineshaft";
    /** A zone of the Dwarven Mines, and the only open-world place Umber and Tungsten are mined. */
    public static final String GLACITE_TUNNELS = "Glacite Tunnels";

    private static final List<Entry> ENTRIES = List.of(
            // Mithril, Breaking Power 4. Drops Mithril and Mithril Powder in the same amount.
            new Entry("gray_wool", Ore.MITHRIL, 1, 500, true, false, Certainty.WIKI),
            new Entry("cyan_terracotta", Ore.MITHRIL, 1, 500, true, false, Certainty.WIKI),
            new Entry("prismarine", Ore.MITHRIL, 2, 800, false, false, Certainty.WIKI),
            new Entry("prismarine_bricks", Ore.MITHRIL, 2, 800, false, false, Certainty.WIKI),
            new Entry("dark_prismarine", Ore.MITHRIL, 2, 800, false, false, Certainty.WIKI),
            new Entry("light_blue_wool", Ore.MITHRIL, 5, 1500, false, false, Certainty.WIKI),
            // Umber, Breaking Power 9, every block strength 5,600. Only plain red_sandstone is
            // listed: whether the cut / chiseled / smooth variants count is not known yet.
            new Entry("terracotta", Ore.UMBER, 1, 5600, false, false, Certainty.WIKI),
            new Entry("brown_terracotta", Ore.UMBER, 2, 5600, false, false, Certainty.WIKI),
            new Entry("red_sandstone", Ore.UMBER, 3, 5600, false, false, Certainty.WIKI),
            // Tungsten, Breaking Power 9, every block strength 5,600.
            new Entry("cobblestone", Ore.TUNGSTEN, 1, 5600, false, false, Certainty.WIKI),
            new Entry("cobblestone_slab", Ore.TUNGSTEN, 0.5, 5600, false, true, Certainty.WIKI),
            new Entry("cobblestone_stairs", Ore.TUNGSTEN, 0.5, 5600, false, true, Certainty.WIKI),
            new Entry("clay", Ore.TUNGSTEN, 3, 5600, false, false, Certainty.WIKI));

    private static final Map<String, Entry> BY_ID;

    static {
        Map<String, Entry> byId = new HashMap<>();
        for (Entry entry : ENTRIES) {
            byId.put(entry.blockId(), entry);
        }
        BY_ID = Collections.unmodifiableMap(byId);
    }

    /**
     * Registry-path words that make an unlisted block worth naming in the arming log: the red
     * sandstone variants and any other look-alike a session turns up, so the table can be checked
     * against a real mine rather than against the wiki.
     */
    private static final List<String> LOOK_ALIKE_WORDS = List.of(
            "red_sandstone", "terracotta", "wool", "prismarine", "cobblestone", "clay");

    private EffectiveBlockTable() {
    }

    /** Every entry, in table order. */
    public static List<Entry> entries() {
        return ENTRIES;
    }

    /** The entry for a block id ({@code "clay"} or {@code "minecraft:clay"}), or {@code null}. */
    public static Entry lookup(String blockId) {
        if (blockId == null) {
            return null;
        }
        String id = blockId.toLowerCase(Locale.ROOT);
        if (id.startsWith("minecraft:")) {
            id = id.substring("minecraft:".length());
        }
        return BY_ID.get(id);
    }

    /** Whether an unlisted block id is close enough to an ore block to be worth logging. */
    public static boolean looksLikeOre(String blockId) {
        if (blockId == null || lookup(blockId) != null) {
            return false;
        }
        for (String word : LOOK_ALIKE_WORDS) {
            if (blockId.contains(word)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ the zone gate

    /**
     * Whether {@code ore} is mined where the player is. These block ids are common decoration on
     * every other island, so anything not listed here is a no: Hub cobblestone and Private Island
     * wool must never be tinted.
     *
     * <p>Mithril: anywhere on the Dwarven Mines (which includes the Glacite Tunnels zone), in the
     * Crystal Hollows (light blue wool only - see {@link #allowedHere}), and in a Mineshaft.
     * Umber and Tungsten: the Glacite Tunnels zone and the Mineshafts only.
     *
     * @param island the island as {@code SkyBlockLocation.island()} reports it
     * @param zone   the zone as {@code SkyBlockLocation.zone()} reports it
     */
    public static boolean oreAllowed(Ore ore, String island, String zone) {
        boolean tunnels = GLACITE_TUNNELS.equalsIgnoreCase(trim(zone));
        boolean mineshaft = isMineshaft(island) || MINESHAFT.equalsIgnoreCase(trim(zone));
        return switch (ore) {
            case MITHRIL -> tunnels || mineshaft
                    || DWARVEN_MINES.equalsIgnoreCase(trim(island))
                    || CRYSTAL_HOLLOWS.equalsIgnoreCase(trim(island));
            case UMBER, TUNGSTEN -> tunnels || mineshaft;
        };
    }

    /**
     * Whether this particular block counts as ore where the player is: its ore is allowed, and in
     * the Crystal Hollows only light blue wool is Mithril there.
     */
    public static boolean allowedHere(Entry entry, String island, String zone) {
        if (!oreAllowed(entry.ore(), island, zone)) {
            return false;
        }
        if (entry.ore() == Ore.MITHRIL && CRYSTAL_HOLLOWS.equalsIgnoreCase(trim(island))) {
            return "light_blue_wool".equals(entry.blockId());
        }
        return true;
    }

    /** The ores allowed where the player is, in declaration order. */
    public static Set<Ore> oresHere(String island, String zone) {
        Set<Ore> ores = EnumSet.noneOf(Ore.class);
        for (Ore ore : Ore.values()) {
            if (oreAllowed(ore, island, zone)) {
                ores.add(ore);
            }
        }
        return ores;
    }

    private static boolean isMineshaft(String island) {
        String name = trim(island);
        return GLACITE_MINESHAFTS.equalsIgnoreCase(name) || MINESHAFT.equalsIgnoreCase(name);
    }

    private static String trim(String text) {
        return text == null ? "" : text.trim();
    }

    // ------------------------------------------------------------------ the ranking

    /**
     * The tier of every enabled block, ranked within its own ore.
     *
     * <p>Blocks with the same score share a rank. The top rank is {@link Tier#BEST}; the bottom
     * rank is {@link Tier#LOW} only when the ore has at least three ranks, so an ore with two
     * (Tungsten without its half blocks) reads best and middle rather than best and "do not
     * bother". Half blocks are left out entirely unless {@code halfBlocks} is set, and so do not
     * push cobblestone down a tier while they are switched off.
     *
     * @param instaMineSoft the player insta-mines gray wool and cyan terracotta: rank them first
     * @param halfBlocks    include the 0.5x slabs and stairs
     */
    public static Map<String, Tier> tiers(boolean instaMineSoft, boolean halfBlocks) {
        Map<String, Tier> tiers = new LinkedHashMap<>();
        for (Ore ore : Ore.values()) {
            List<Entry> blocks = new ArrayList<>();
            TreeSet<Double> ranks = new TreeSet<>(Collections.reverseOrder());
            for (Entry entry : ENTRIES) {
                if (entry.ore() != ore || (entry.halfBlock() && !halfBlocks)) {
                    continue;
                }
                blocks.add(entry);
                ranks.add(rankScore(entry, instaMineSoft));
            }
            List<Double> ordered = new ArrayList<>(ranks);
            for (Entry entry : blocks) {
                int rank = ordered.indexOf(rankScore(entry, instaMineSoft));
                Tier tier;
                if (rank == 0) {
                    tier = Tier.BEST;
                } else if (rank == ordered.size() - 1 && ordered.size() >= 3) {
                    tier = Tier.LOW;
                } else {
                    tier = Tier.MIDDLE;
                }
                tiers.put(entry.blockId(), tier);
            }
        }
        return tiers;
    }

    /** The value a block is ranked by: its score, or above every score when it is insta-mined. */
    private static double rankScore(Entry entry, boolean instaMineSoft) {
        return instaMineSoft && entry.soft() ? Double.MAX_VALUE : entry.score();
    }
}
