/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import sbs.modid.client.helper.customskin.logic.CustomSkinStore;

/**
 * Custom Skin for <b>worn</b> armour: makes a skinned armour piece render as the chosen piece on the
 * player model, not only as an inventory icon.
 *
 * <p>Armour on a body is not drawn from the item's model at all – it is drawn from the equipment
 * asset its {@code minecraft:equippable} component points at, cut into helmet/chest/legs/boots
 * layers. That is why a piece can only be re-skinned as <b>another armour piece for the same slot</b>:
 * a sword has no equipment asset and no armour layers, so there is nothing to draw a leg or a chest
 * from. Substituting one anyway is not "renders wrong", it is "renders nothing" – the player would
 * silently lose their armour on screen.
 *
 * <p>So the swap happens here only when the skin is equippable in the same slot and actually has an
 * asset; anything else leaves the worn render untouched while the item's icon and hand model are
 * still re-skinned by {@link CustomSkinModelMixin}. The picker states this next to the choice rather
 * than forbidding it.
 */
@Mixin(HumanoidArmorLayer.class)
public abstract class CustomSkinArmorMixin {

    @ModifyVariable(method = "renderArmorPiece", at = @At("HEAD"), argsOnly = true)
    private ItemStack skyblockSimplified$skinWornArmor(ItemStack stack) {
        if (!sbs.modid.client.core.config.ConfigManager.getInstance().get().customSkin.applyToWornArmor) {
            return stack;
        }
        ItemStack skin = CustomSkinStore.getInstance().skinFor(stack);
        if (skin == null) {
            return stack;
        }
        Equippable original = stack.get(DataComponents.EQUIPPABLE);
        Equippable replacement = skin.get(DataComponents.EQUIPPABLE);
        if (original == null || replacement == null
                || original.slot() != replacement.slot() || replacement.assetId().isEmpty()) {
            return stack;
        }
        return skin;
    }
}
