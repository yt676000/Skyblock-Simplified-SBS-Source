/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Write access to the "using an item" state of a living entity, for the Loadouts preview model's
 * mirror (bow draw, eating, blocking). The state is protected in vanilla and normally only set by the
 * entity's own tick, which the preview deliberately never runs. Names javap-verified against 26.2:
 * {@code useItem}, {@code useItemRemaining}, {@code setLivingEntityFlag(int, boolean)}.
 */
@Mixin(LivingEntity.class)
public interface LivingEntityUseAccessor {

    @Accessor("useItem")
    void sbs$setUseItem(ItemStack stack);

    @Accessor("useItemRemaining")
    void sbs$setUseItemRemaining(int ticks);

    /** Flag 1 = using an item, 2 = with the off hand (vanilla's own bit values). */
    @Invoker("setLivingEntityFlag")
    void sbs$setLivingEntityFlag(int flag, boolean value);
}
