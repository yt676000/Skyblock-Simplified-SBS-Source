/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import sbs.modid.client.helper.customskin.logic.CustomSkinStore;

/**
 * The Custom Skin module's render hook: makes an item render as the item you picked for it.
 *
 * <p>{@code ItemModelResolver.appendItemLayers} is the one place every item's model is resolved –
 * the inventory icon, the item in your hand, dropped items, item frames and armour stands all reach
 * it – so swapping the stack here on entry re-skins all of them at once, with the full appearance of
 * the chosen item (model, skull texture, dye, glint) instead of just its texture.
 *
 * <p>Names and tooltips deliberately do <b>not</b> change: they are read straight off the real stack
 * and never pass through the model resolver. Nothing is written into the item either – the swap
 * lives for the duration of this call only, so the server never sees anything.
 */
@Mixin(ItemModelResolver.class)
public abstract class CustomSkinModelMixin {

    @ModifyVariable(method = "appendItemLayers", at = @At("HEAD"), argsOnly = true)
    private ItemStack skyblockSimplified$applyCustomSkin(ItemStack stack) {
        ItemStack skin = CustomSkinStore.getInstance().skinFor(stack);
        return skin != null ? skin : stack;
    }
}
