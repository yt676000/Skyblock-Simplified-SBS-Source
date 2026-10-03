/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.lookingat;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import sbs.modid.client.core.location.SkyBlockLocation;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * What a vanilla block <i>is</i> in SkyBlock: gray wool in the Dwarven Mines is Mithril, diamond ore
 * there is Titanium, red stained glass in the Crystal Hollows is a Ruby gemstone.
 *
 * <p><b>Why this has to be a table.</b> Hypixel builds its custom ores out of ordinary vanilla
 * blocks and ships nothing on the block itself to say so - no block entity, no state property, no
 * component. Client-side, a Mithril vein and a decorative gray wool block are byte-for-byte the same
 * block. The only thing that separates them is <i>where you are standing</i>, so every mapping here
 * is filed under the island it is true on and nothing is ever renamed off-island. That is what stops
 * the wool in your own island's build from being announced as Mithril.
 *
 * <p><b>Accuracy.</b> The Dwarven Mines entries and the six original Crystal Hollows gemstones are
 * the well-established ones. The later gemstones (Opal, Onyx, Aquamarine, Citrine, Peridot) and the
 * Glacite entries are best-effort: they are placed by colour, which is how Hypixel has assigned
 * every gemstone so far, but they have not been verified against the live game. A wrong entry here
 * is cosmetic and one line to correct - the table is deliberately flat and readable for exactly that
 * reason.
 */
public final class SkyblockBlockNames {

    /** island (lower-case) → block registry id → the SkyBlock name to show instead. */
    private static final Map<String, Map<String, String>> BY_ISLAND = new HashMap<>();

    private SkyblockBlockNames() {
    }

    private static void put(String island, String blockId, String name) {
        BY_ISLAND.computeIfAbsent(island.toLowerCase(Locale.ROOT), key -> new HashMap<>())
                .put("minecraft:" + blockId, name);
    }

    /** Registers one mapping on several islands at once (the ore sets overlap heavily). */
    private static void put(String[] islands, String blockId, String name) {
        for (String island : islands) {
            put(island, blockId, name);
        }
    }

    static {
        // Island names only - these are matched against SkyBlockLocation.island(), which resolves
        // an area ("Dwarven Base Camp", "Glacite Tunnels") up to the island it belongs to, so
        // listing an area here would simply never match anything.
        String[] mithrilIslands = {"Dwarven Mines", "Crystal Hollows", "Glacite Mineshafts"};
        // Mithril comes in three colours; all three are simply "Mithril" when you mine them, and
        // the grey one is what the Dwarven Mines is mostly built out of.
        put(mithrilIslands, "gray_wool", "Mithril");
        put(mithrilIslands, "light_gray_wool", "Mithril");
        put(mithrilIslands, "prismarine", "Mithril");
        put(mithrilIslands, "light_blue_wool", "Mithril");
        put(mithrilIslands, "stone", "Hard Stone");
        put(mithrilIslands, "andesite", "Hard Stone");
        // Titanium hides among the mithril as diamond ore - the whole point of the commission.
        put(mithrilIslands, "diamond_ore", "Titanium");
        put(mithrilIslands, "deepslate_diamond_ore", "Titanium");

        // Crystal Hollows gemstones: stained glass, and its panes at the smaller vein sizes.
        gemstone("red", "Ruby");
        gemstone("orange", "Amber");
        gemstone("purple", "Amethyst");
        gemstone("lime", "Jade");
        gemstone("light_blue", "Sapphire");
        gemstone("yellow", "Topaz");
        gemstone("magenta", "Jasper");
        // Later additions - placed by colour, not yet confirmed in game.
        gemstone("white", "Opal");
        gemstone("black", "Onyx");
        gemstone("blue", "Aquamarine");
        gemstone("brown", "Citrine");
        gemstone("green", "Peridot");

        // Glacite: the Mineshafts, and the Great Ice Wall / Glacite Tunnels up in the Dwarven Mines.
        String[] glacite = {"Glacite Mineshafts", "Dwarven Mines"};
        put(glacite, "packed_ice", "Glacite");
        put(glacite, "blue_ice", "Glacite");
    }

    /** A gemstone occupies both the full block and the pane, in the same colour. */
    private static void gemstone(String color, String name) {
        put("Crystal Hollows", color + "_stained_glass", name);
        put("Crystal Hollows", color + "_stained_glass_pane", name);
    }

    /**
     * The SkyBlock name for a block <b>at the player's current location</b>, or {@code null} when
     * the block is just itself. Returns {@code null} off SkyBlock entirely, so a singleplayer world
     * never sees a SkyBlock name.
     */
    public static String nameFor(BlockState state) {
        if (state == null) {
            return null;
        }
        String island = SkyBlockLocation.island();
        if (island == null || island.isEmpty()) {
            return null;
        }
        Map<String, String> onIsland = BY_ISLAND.get(island.toLowerCase(Locale.ROOT));
        if (onIsland == null) {
            return null;
        }
        return onIsland.get(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
    }
}
