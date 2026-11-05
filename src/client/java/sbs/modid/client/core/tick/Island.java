/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.tick;

/**
 * The SkyBlock islands a tick gate can name, spelled the way {@code SkyBlockLocation.onIsland} and
 * {@code IslandCatalog} spell them. Which ones the player is on is decided by
 * {@code SkyBlockLocation.onIsland(displayName)} - the exact test the features already use - once per
 * location change in {@link TickContexts}, and kept as a bit mask in {@link TickContext}.
 *
 * <p>At most 64 values (one {@code long}).
 */
public enum Island {
    PRIVATE_ISLAND("Private Island"),
    GARDEN("The Garden"),
    HUB("Hub"),
    FARMING_ISLANDS("The Farming Islands"),
    PARK("The Park"),
    SPIDERS_DEN("Spider's Den"),
    END("The End"),
    CRIMSON_ISLE("Crimson Isle"),
    GOLD_MINE("Gold Mine"),
    DEEP_CAVERNS("Deep Caverns"),
    DWARVEN_MINES("Dwarven Mines"),
    CRYSTAL_HOLLOWS("Crystal Hollows"),
    GLACITE_MINESHAFTS("Glacite Mineshafts"),
    DUNGEON_HUB("Dungeon Hub"),
    CATACOMBS("The Catacombs"),
    RIFT("The Rift"),
    JERRYS_WORKSHOP("Jerry's Workshop"),
    BACKWATER_BAYOU("Backwater Bayou"),
    MOONGLADE_MARSH("Moonglade Marsh"),
    TORRHUS_CANYON("Torrhus Canyon"),
    CRITTER_SAFARI("Critter Safari"),
    LOTUS_ATOLL("Lotus Atoll"),
    KUUDRAS_HOLLOW("Kuudra's Hollow");

    private static final Island[] VALUES = values();

    /** The name {@code SkyBlockLocation.onIsland} is asked with. */
    public final String displayName;

    /** This island's bit in {@link TickContext#islands()}. */
    public final long bit;

    Island(String displayName) {
        this.displayName = displayName;
        this.bit = 1L << ordinal();
    }

    /** Every island, shared - callers must not change it. */
    static Island[] all() {
        return VALUES;
    }
}
