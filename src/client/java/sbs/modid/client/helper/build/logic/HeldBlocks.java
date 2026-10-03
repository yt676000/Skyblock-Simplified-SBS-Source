/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.level.block.state.BlockState;
import sbs.modid.client.core.build.logic.BlockStates;
import sbs.modid.client.helper.build.model.BlockPattern;
import sbs.modid.client.helper.build.model.HeldBlockRule;

/**
 * {@code hand} / {@code offhand} for the pattern parser: the block of the item in that hand. A block
 * item gives its block's default state, with any block-state data the stack carries applied
 * ({@code DataComponents.BLOCK_STATE}, e.g. a picked slab or stair) - so what is placed looks like what
 * is held. Only read: nothing about the stack changes.
 */
public final class HeldBlocks {

    public static final BlockPattern.HeldBlocks RESOLVER = HeldBlocks::held;

    private HeldBlocks() {
    }

    private static BlockPattern.Held held(boolean offhandToken) throws BlockPattern.PatternException {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            throw new BlockPattern.PatternException(HeldBlockRule.NO_BLOCK);
        }
        boolean viaStick = !offhandToken && MagicStick.is(player.getMainHandItem());
        ItemStack stack = HeldBlockRule.readsOffhand(offhandToken, viaStick) ? player.getOffhandItem() : player.getMainHandItem();
        String refusal = HeldBlockRule.refusal(stack.isEmpty(), stack.getItem() instanceof BlockItem, viaStick);
        if (refusal != null) {
            throw new BlockPattern.PatternException(refusal);
        }
        BlockItem item = (BlockItem) stack.getItem();
        BlockState state = item.getBlock().defaultBlockState();
        BlockItemStateProperties properties = stack.get(DataComponents.BLOCK_STATE);
        if (properties != null && !properties.isEmpty()) {
            state = properties.apply(state);
        }
        return new BlockPattern.Held(BlockStates.serialize(state), state.getBlock().getName().getString());
    }
}
