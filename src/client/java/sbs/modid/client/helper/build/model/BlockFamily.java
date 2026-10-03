/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.model;

import sbs.modid.client.core.build.model.StateStrings;

import java.util.List;
import java.util.Set;

/**
 * Which blocks belong together for smart select: a block's family is its id with the shape
 * stripped - {@code oak_planks}, {@code oak_stairs} and {@code oak_fence} are all {@code oak};
 * {@code stone_bricks} and {@code stone_brick_wall} are both {@code stone_brick}.
 *
 * <p>Also names the natural terrain a build stands on ({@link #isTerrain}), so the default smart
 * select stops at the ground instead of flooding into it.
 */
public final class BlockFamily {

    /** Shape suffixes, longest first so {@code _fence_gate} wins over {@code _gate}. */
    private static final List<String> SHAPES = List.of(
            "_fence_gate", "_pressure_plate", "_hanging_sign", "_wall_sign", "_trapdoor", "_stairs",
            "_slab", "_wall", "_fence", "_door", "_button", "_sign", "_planks", "_log", "_wood", "_leaves");

    /** The ground and water around a build - never part of it for the default smart select. */
    private static final Set<String> TERRAIN = Set.of(
            "grass_block", "dirt", "coarse_dirt", "rooted_dirt", "podzol", "mycelium", "dirt_path", "farmland",
            "stone", "deepslate", "granite", "diorite", "andesite", "tuff", "calcite", "gravel", "sand",
            "red_sand", "sandstone", "red_sandstone", "clay", "water", "lava", "bedrock", "netherrack",
            "end_stone", "snow", "snow_block", "ice", "packed_ice", "short_grass", "tall_grass", "fern",
            "large_fern", "seagrass", "tall_seagrass", "kelp", "kelp_plant", "mud", "moss_block");

    private BlockFamily() {
    }

    public static String of(String state) {
        String id = StateStrings.blockId(state);
        String path = id.substring(id.indexOf(':') + 1);
        if (path.startsWith("stripped_")) {
            path = path.substring("stripped_".length());
        }
        for (String shape : SHAPES) {
            if (path.endsWith(shape) && path.length() > shape.length()) {
                return path.substring(0, path.length() - shape.length());
            }
        }
        if (path.endsWith("_bricks")) {
            return path.substring(0, path.length() - 1);
        }
        if (path.endsWith("s") && path.endsWith("_tiles")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }

    public static boolean isTerrain(String state) {
        String id = StateStrings.blockId(state);
        return TERRAIN.contains(id.substring(id.indexOf(':') + 1));
    }
}
