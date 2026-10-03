/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.location.SkyBlockLocation;

import java.util.List;

/**
 * The island whitelists that keep the Farming and Mining features on the islands they are about.
 *
 * <p>Both modules follow what is in your hand, not where you stand, so without a gate the crop
 * milestone card counts pumpkins you break in a dungeon and the mining routes hang in the air over
 * the Hub. The whitelists are the "where does this activity actually happen" answer, and each module
 * has a toggle (default on) to switch the gate off for the players who do farm in odd places.
 *
 * <p>Location questions all go through {@link SkyBlockLocation}, which reconciles the tab list's
 * island line with the scoreboard's zone line (and the Catacombs' dungeon line) and does its own
 * caching - so an entry here may name an island ("The Garden") or a single zone ("Coal Mine") and
 * both just work.
 */
public final class SkillIslands {

    /**
     * Where crops are farmed. {@code Farm} is a Hub sub-area (and matches the Rift's Dreadfarm, which
     * is also a farm); {@code Your Island} is what the scoreboard calls the private island, whose tab
     * line says "Private Island" instead - both spellings are listed so either source can answer.
     */
    public static final List<String> FARMING_ISLANDS = List.of(
            "The Garden",
            "The Farming Islands",
            "Private Island",
            "Your Island",
            "Farm");

    /**
     * Where ore is mined. {@code Mineshaft} is its own entry because a Glacite Mineshaft is a private
     * instance whose area name resolves to no island; {@code Coal Mine} is the Hub's mine, so the rest
     * of the Hub stays outside the whitelist.
     */
    public static final List<String> MINING_ISLANDS = List.of(
            "Gold Mine",
            "Deep Caverns",
            "Dwarven Mines",
            "Crystal Hollows",
            "Glacite Mineshafts",
            "Mineshaft",
            "Coal Mine");

    /**
     * Where foraging happens: Galatea's two islands. {@code Galatea} itself is listed as well because
     * it is the region's scoreboard zone rather than an island - the tab list's {@code Area:} line says
     * "Moonglade Marsh" or "Torrhus Canyon" and never "Galatea", so all three spellings are needed for
     * either source to be able to answer.
     */
    public static final List<String> FORAGING_ISLANDS = List.of(
            "Moonglade Marsh",
            "Torrhus Canyon",
            "Galatea");

    private SkillIslands() {
    }

    /** Whether the Farming module's features may run right now. */
    public static boolean farmingAllowed() {
        return !ConfigManager.getInstance().get().farming.islandLock || onAny(FARMING_ISLANDS);
    }

    /** Whether the Mining modules' features may run right now. */
    public static boolean miningAllowed() {
        return !ConfigManager.getInstance().get().mining.islandLock || onAny(MINING_ISLANDS);
    }

    /** Whether the Foraging modules' features may run right now. */
    public static boolean foragingAllowed() {
        return !ConfigManager.getInstance().get().foraging.islandLock || onAny(FORAGING_ISLANDS);
    }

    /** The whitelist as one line, for the settings label under each toggle. */
    public static String describe(List<String> islands) {
        return String.join(", ", islands);
    }

    /** Whether the player is on a farming island - regardless of the island-lock setting. */
    public static boolean onFarmingIsland() {
        return onAny(FARMING_ISLANDS);
    }

    /** Whether the player is on a mining island - regardless of the island-lock setting. */
    public static boolean onMiningIsland() {
        return onAny(MINING_ISLANDS);
    }

    /** Whether the live location matches any entry of the whitelist. */
    private static boolean onAny(List<String> islands) {
        for (String island : islands) {
            if (SkyBlockLocation.matches(island)) {
                return true;
            }
        }
        return false;
    }
}
