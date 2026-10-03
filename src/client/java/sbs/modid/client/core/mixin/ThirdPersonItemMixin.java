/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

/**
 * The Animation &amp; Scaling module's held-item size / offset in <b>third person</b>: the same
 * transform the first-person hook ({@link HeldItemScaleMixin}) applies, but on the player-model
 * hand item ({@code ItemInHandLayer}). Applied right after {@code translateToHand} anchors the
 * pose at the hand – inside the layer's own push/pop – so the item grows/shrinks around the hand
 * exactly like in first person. Only player models are affected (your own F5 view and other
 * players), mobs keep their vanilla hand items.
 */
@Mixin(ItemInHandLayer.class)
public abstract class ThirdPersonItemMixin {

    private static SBSConfig.AnimationScalingSettings sbs$cfg() {
        return ConfigManager.getInstance().get().animationScaling;
    }

    private static boolean sbs$applies(ArmedEntityRenderState state) {
        return sbs$cfg().itemViewMode.thirdPerson() && state instanceof AvatarRenderState;
    }

    @Inject(method = "submitArmWithItem", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$hideItem(ArmedEntityRenderState state, ItemStackRenderState itemState,
                                             ItemStack stack, HumanoidArm arm, PoseStack poseStack,
                                             SubmitNodeCollector collector, int light, CallbackInfo ci) {
        if (sbs$applies(state) && sbs$cfg().itemSize <= 0) {
            ci.cancel(); // 0% – the held item is invisible
        }
    }

    @Inject(method = "submitArmWithItem", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/client/model/ArmedModel;translateToHand(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lnet/minecraft/world/entity/HumanoidArm;Lcom/mojang/blaze3d/vertex/PoseStack;)V"))
    private void skyblockSimplified$transformItem(ArmedEntityRenderState state, ItemStackRenderState itemState,
                                                  ItemStack stack, HumanoidArm arm, PoseStack poseStack,
                                                  SubmitNodeCollector collector, int light, CallbackInfo ci) {
        if (!sbs$applies(state)) {
            return;
        }
        SBSConfig.AnimationScalingSettings settings = sbs$cfg();
        // The state carries which side is the main arm, so an entity with a left-handed skin
        // setting gets the same hand-to-offset mapping as the first-person view does.
        boolean mainHand = arm == state.mainArm;
        int offsetX = settings.handOffsetX(mainHand);
        int offsetY = settings.handOffsetY(mainHand);
        int offsetZ = settings.handOffsetZ(mainHand);
        if (offsetX != 0 || offsetY != 0 || offsetZ != 0) {
            poseStack.translate(offsetX / 100.0F, offsetY / 100.0F, offsetZ / 100.0F);
        }
        int percent = settings.itemSize;
        if (percent != 100 && percent > 0) {
            float scale = Math.min(percent, 1000) / 100.0F;
            poseStack.scale(scale, scale, scale);
        }
    }
}
