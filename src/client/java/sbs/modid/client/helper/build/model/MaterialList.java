/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.model;

import sbs.modid.client.core.build.model.StateStrings;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * What you need in hand to build a set of block states: the items, counted.
 *
 * <p>A block state is not always one item: a double slab is two slabs, a door is one item for two
 * blocks, a torch on a wall is still a torch, a potted flower is a pot and a flower, and pistons'
 * heads or portal blocks are nothing you can carry. These rules are pure string work over the ids,
 * so the list is tested without the game; an id they do not know counts as its own item.
 */
public final class MaterialList {

    /** Blocks nobody places by hand, so they never appear on the list. */
    private static final Set<String> NOT_ITEMS = Set.of(
            "minecraft:air", "minecraft:cave_air", "minecraft:void_air", "minecraft:structure_void",
            "minecraft:piston_head", "minecraft:moving_piston", "minecraft:fire", "minecraft:soul_fire",
            "minecraft:nether_portal", "minecraft:end_portal", "minecraft:end_gateway",
            "minecraft:bubble_column", "minecraft:frosted_ice");

    /** Two-block plants and blocks whose upper half is not a second item. */
    private static final Set<String> TWO_HIGH = Set.of(
            "minecraft:tall_grass", "minecraft:large_fern", "minecraft:sunflower", "minecraft:lilac",
            "minecraft:rose_bush", "minecraft:peony", "minecraft:tall_seagrass", "minecraft:pitcher_plant",
            "minecraft:small_dripleaf");

    private MaterialList() {
    }

    /** One line of the list. */
    public record Line(String item, long count) {
    }

    /**
     * Counts items for {@code states} (state string to block count), largest first. The ties break
     * by id so the list is stable between calls.
     */
    public static List<Line> of(Map<String, Integer> states) {
        Map<String, Long> items = new TreeMap<>();
        for (Map.Entry<String, Integer> entry : states.entrySet()) {
            for (Map.Entry<String, Integer> item : itemsFor(entry.getKey()).entrySet()) {
                items.merge(item.getKey(), (long) item.getValue() * entry.getValue(), Long::sum);
            }
        }
        return items.entrySet().stream()
                .map(entry -> new Line(entry.getKey(), entry.getValue()))
                .sorted((a, b) -> a.count() != b.count() ? Long.compare(b.count(), a.count()) : a.item().compareTo(b.item()))
                .toList();
    }

    /** The items one block of {@code state} takes (usually one item, one of it; sometimes none). */
    public static Map<String, Integer> itemsFor(String state) {
        String id = StateStrings.blockId(state);
        Map<String, String> properties = StateStrings.properties(state);
        Map<String, Integer> out = new LinkedHashMap<>();
        if (NOT_ITEMS.contains(id)) {
            return out;
        }
        // The upper half of a door, a tall plant, or the head of a bed is the same item as its other half.
        if ((id.endsWith("_door") || TWO_HIGH.contains(id)) && "upper".equals(properties.get("half"))) {
            return out;
        }
        if (id.endsWith("_bed") && "head".equals(properties.get("part"))) {
            return out;
        }
        if (id.equals("minecraft:water") || id.equals("minecraft:lava")) {
            // Only a source block is a bucket; flowing fluid comes by itself.
            if ("0".equals(properties.getOrDefault("level", "0"))) {
                out.put(id + "_bucket", 1);
            }
            return out;
        }
        if (id.endsWith("_slab") && "double".equals(properties.get("type"))) {
            out.put(id, 2);
            return out;
        }
        if (id.startsWith("minecraft:potted_")) {
            out.put("minecraft:flower_pot", 1);
            out.put("minecraft:" + id.substring("minecraft:potted_".length()), 1);
            return out;
        }
        out.put(itemIdFor(id), 1);
        return out;
    }

    /** Wall-mounted and placed forms back to the item that places them. */
    static String itemIdFor(String id) {
        return switch (id) {
            case "minecraft:wall_torch" -> "minecraft:torch";
            case "minecraft:soul_wall_torch" -> "minecraft:soul_torch";
            case "minecraft:redstone_wall_torch" -> "minecraft:redstone_torch";
            case "minecraft:redstone_wire" -> "minecraft:redstone";
            case "minecraft:tripwire" -> "minecraft:string";
            case "minecraft:cocoa" -> "minecraft:cocoa_beans";
            case "minecraft:carrots" -> "minecraft:carrot";
            case "minecraft:potatoes" -> "minecraft:potato";
            case "minecraft:beetroots" -> "minecraft:beetroot_seeds";
            case "minecraft:wheat" -> "minecraft:wheat_seeds";
            case "minecraft:melon_stem", "minecraft:attached_melon_stem" -> "minecraft:melon_seeds";
            case "minecraft:pumpkin_stem", "minecraft:attached_pumpkin_stem" -> "minecraft:pumpkin_seeds";
            case "minecraft:sweet_berry_bush" -> "minecraft:sweet_berries";
            case "minecraft:bamboo_sapling" -> "minecraft:bamboo";
            default -> {
                if (id.endsWith("_wall_sign")) {
                    yield id.replace("_wall_sign", "_sign");
                }
                if (id.endsWith("_wall_hanging_sign")) {
                    yield id.replace("_wall_hanging_sign", "_hanging_sign");
                }
                if (id.endsWith("_wall_banner")) {
                    yield id.replace("_wall_banner", "_banner");
                }
                if (id.endsWith("_wall_head")) {
                    yield id.replace("_wall_head", "_head");
                }
                if (id.endsWith("_wall_skull")) {
                    yield id.replace("_wall_skull", "_skull");
                }
                if (id.endsWith("_wall_fan")) {
                    yield id.replace("_wall_fan", "_fan");
                }
                yield id;
            }
        };
    }
}
