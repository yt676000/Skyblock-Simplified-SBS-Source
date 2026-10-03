/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read access to container GUI internals: the bounds ({@code leftPos}/{@code imageWidth}/
 * {@code imageHeight}) for the Recipe Viewer panel and the SBS container theme, and the hovered
 * slot for the Ender Chest / Backpack preview.
 */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {

    @Accessor("leftPos")
    int skyblockSimplified$leftPos();

    @Accessor("topPos")
    int skyblockSimplified$topPos();

    @Accessor("imageWidth")
    int skyblockSimplified$imageWidth();

    @Accessor("imageHeight")
    int skyblockSimplified$imageHeight();

    /** Inventory Window moves the panel by writing the position vanilla hit-tests against. */
    @Accessor("leftPos")
    void skyblockSimplified$setLeftPos(int leftPos);

    @Accessor("topPos")
    void skyblockSimplified$setTopPos(int topPos);

    @Accessor("hoveredSlot")
    net.minecraft.world.inventory.Slot skyblockSimplified$hoveredSlot();
}
