/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.component.CustomData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read access to the tag inside a {@link CustomData} component. 26.2 exposes it only through
 * {@code copyTag()}, a full deep copy, which every SkyBlock id read paid for per slot per frame.
 *
 * <p>The returned tag is the component's own: it must never be modified. Go through
 * {@code SkyblockItem.customTag}, which is the one caller.
 */
@Mixin(CustomData.class)
public interface CustomDataAccessor {

    @Accessor("tag")
    CompoundTag skyblockSimplified$tag();
}
