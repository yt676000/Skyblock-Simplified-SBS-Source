/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.farming.model;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Which {@link CropType} a broken block is, for counting crops broken.
 *
 * <p>The vanilla blocks behind the classic crops are certain. The three flower crops are not: the
 * table maps them by the item material in Hypixel's items resource - Sunflower {@code DOUBLE_PLANT:0},
 * Wild Rose {@code DOUBLE_PLANT:4} (rose bush), Moonflower {@code RED_ROSE:1} (blue orchid) - and
 * counts those blocks whatever is held, because on the Garden (the only place the card counts)
 * every such flower is a crop. Whether the <i>placed</i> block is the item's material is unverified.
 * Any other flower still counts only with a flower tool in hand, filed under that tool's crop.
 * "Log Broken Blocks" on the Farming page is how to settle the real block ids.
 */
public final class CropBlocks {

    private static final Map<Block, CropType> BY_BLOCK = new IdentityHashMap<>();

    static {
        BY_BLOCK.put(Blocks.WHEAT, CropType.WHEAT);
        BY_BLOCK.put(Blocks.CARROTS, CropType.CARROT);
        BY_BLOCK.put(Blocks.POTATOES, CropType.POTATO);
        BY_BLOCK.put(Blocks.NETHER_WART, CropType.NETHER_WART);
        BY_BLOCK.put(Blocks.SUGAR_CANE, CropType.SUGAR_CANE);
        BY_BLOCK.put(Blocks.MELON, CropType.MELON);
        BY_BLOCK.put(Blocks.PUMPKIN, CropType.PUMPKIN);
        BY_BLOCK.put(Blocks.COCOA, CropType.COCOA_BEANS);
        BY_BLOCK.put(Blocks.CACTUS, CropType.CACTUS);
        BY_BLOCK.put(Blocks.RED_MUSHROOM, CropType.MUSHROOM);
        BY_BLOCK.put(Blocks.BROWN_MUSHROOM, CropType.MUSHROOM);
        // UNVERIFIED: assumed from the crop names.
        BY_BLOCK.put(Blocks.SUNFLOWER, CropType.SUNFLOWER);
        BY_BLOCK.put(Blocks.ROSE_BUSH, CropType.WILD_ROSE);
        BY_BLOCK.put(Blocks.BLUE_ORCHID, CropType.MOONFLOWER);
    }

    private CropBlocks() {
    }

    /**
     * The crop {@code state} is, or {@code null} when it is not a crop block.
     *
     * @param heldCrop the crop of the tool in hand ({@link CropType#forTool}), or {@code null}
     */
    public static CropType of(BlockState state, CropType heldCrop) {
        if (state == null) {
            return null;
        }
        CropType crop = BY_BLOCK.get(state.getBlock());
        if (crop != null) {
            return crop;
        }
        if ((heldCrop == CropType.SUNFLOWER || heldCrop == CropType.WILD_ROSE)
                && state.is(BlockTags.FLOWERS)) {
            return heldCrop;
        }
        return null;
    }
}
