/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.special.PlayerHeadSpecialRenderer;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.helper.texture.logic.SkyblockItemModels;

/**
 * Restores the classic skull textures of head-based SkyBlock items while "Ignore Enforced Texture
 * Packs" is on: with the pack blocked those stacks carry no profile component (the pack used to
 * provide the texture), so heads would render as the default skin. The wrap substitutes the item's
 * pre-pack head texture from {@link SkyblockItemModels} – applied skins (ExtraAttributes {@code
 * skin}) win over the base id. Real player heads are left alone.
 */
@Mixin(PlayerHeadSpecialRenderer.class)
public abstract class HeadProfileRemapMixin {

    @WrapOperation(
            method = "extractArgument(Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/client/renderer/PlayerSkinRenderCache$RenderInfo;",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/item/ItemStack;get(Lnet/minecraft/core/component/DataComponentType;)Ljava/lang/Object;"))
    private Object skyblockSimplified$classicSkull(ItemStack stack, DataComponentType<?> type,
                                                   Operation<Object> original) {
        Object current = original.call(stack, type);
        if (stack.isEmpty() || stack.is(Items.PLAYER_HEAD)
                || !ConfigManager.getInstance().get().texturePack.ignoreEnforcedPacks) {
            return current;
        }
        String skyblockId = SkyblockItemModels.skyblockId(stack);
        if (skyblockId == null) {
            return current;
        }
        ResolvableProfile skin = SkyblockItemModels.skullFor(SkyblockItemModels.skinId(stack));
        if (skin != null) {
            return skin;
        }
        ResolvableProfile classic = SkyblockItemModels.skullFor(skyblockId);
        return classic != null ? classic : current;
    }
}
