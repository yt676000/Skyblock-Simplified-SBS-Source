/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.stacktips;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.core.item.StackCache;

/**
 * A live stack's {@link DungeonQuality}, read once per stack and custom-data instance through a
 * {@link StackCache}: the stack tip and the tooltip line share one read, and the attribute compound
 * is the stack's own tag ({@link SkyblockItem#extraAttributes}), never a {@code copyTag()}.
 *
 * <p>Kept apart from {@link DungeonQuality} so that one stays free of game classes for unit tests.
 * Render thread only, like {@link StackCache}.
 */
final class DungeonQualityReader {

    private static final StackCache<DungeonQuality> CACHE = new StackCache<>(512,
            DungeonQualityReader::compute, DataComponents.CUSTOM_DATA, DataComponents.LORE);

    private DungeonQualityReader() {
    }

    /** The stack's quality roll, or {@code null} for none. */
    static DungeonQuality of(ItemStack stack) {
        return stack == null || stack.isEmpty() ? null : CACHE.get(stack);
    }

    private static DungeonQuality compute(ItemStack stack) {
        CompoundTag extra = SkyblockItem.extraAttributes(stack);
        DungeonQualityProbe.observe(stack, extra);
        return DungeonQuality.read(extra);
    }
}
