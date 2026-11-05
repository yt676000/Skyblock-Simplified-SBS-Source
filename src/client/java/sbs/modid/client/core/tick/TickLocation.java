/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.tick;

/**
 * Where the player is, as every tick gate asks it - computed by {@link TickContexts} only when the
 * sidebar or the tab list changed, and shared unchanged between ticks otherwise.
 *
 * <p>Each flag is the answer of the function the features themselves already ask (named on the
 * accessor), evaluated once - so a gate on a flag means exactly what the feature's own early return
 * means, and nothing reads the sidebar a second time to find out.
 *
 * @param island         {@code SkyBlockLocation.island()}, {@code ""} when unreadable
 * @param zone           {@code SkyBlockLocation.zone()}, {@code ""} off SkyBlock
 * @param islands        the {@link Island#bit}s {@code SkyBlockLocation.onIsland} answers yes for
 * @param inCatacombs    {@code DungeonScoreboard.isInCatacombs()} - the dungeon gate, entrance included
 * @param inDungeonFloor {@code DungeonScoreboard.isInDungeon()} - a floor marker ("(F6)") is up
 * @param miningIsland   {@code SkillIslands.onMiningIsland()} (zone substrings: Coal Mine, Mineshaft)
 * @param farmingIsland  {@code SkillIslands.onFarmingIsland()}
 * @param foragingIsland whether Galatea's islands or zone match, as {@code SkillIslands} spells them
 * @param inTheMist      {@code MistVisuals.inTheMist()}
 * @param inSafariArea   {@code SafariTracker.inSafariArea()} (the zone or the island)
 * @param floorDropArea  {@code FloorDropAreas.live()}
 */
public record TickLocation(String island, String zone, long islands, boolean inCatacombs,
                           boolean inDungeonFloor, boolean miningIsland, boolean farmingIsland,
                           boolean foragingIsland, boolean inTheMist, boolean inSafariArea,
                           boolean floorDropArea) {

    /** Nowhere: no world, or no SkyBlock sidebar and tab list. */
    public static final TickLocation NONE =
            new TickLocation("", "", 0L, false, false, false, false, false, false, false, false);

    /** Whether either half of the location can be read - i.e. this is SkyBlock. */
    public boolean inSkyBlock() {
        return !island.isEmpty() || !zone.isEmpty();
    }

    /** Whether {@code SkyBlockLocation.onIsland(island.displayName)} said yes. */
    public boolean on(Island island) {
        return (islands & island.bit) != 0;
    }
}
