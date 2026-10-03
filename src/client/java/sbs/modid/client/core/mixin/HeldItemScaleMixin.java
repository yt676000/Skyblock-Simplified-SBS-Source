/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.core.config.ConfigManager;

/**
 * The Animation &amp; Scaling module's <b>item size</b>: scales the first-person held item by the
 * configured percentage. The scale is applied to the pose right before the in-hand item renders,
 * so it grows/shrinks around its hand anchor – 100% is vanilla, 1000% is ten times larger and 0%
 * hides the item completely (the render is skipped).
 */
@Mixin(ItemInHandRenderer.class)
public abstract class HeldItemScaleMixin {

    @Inject(method = "renderItem", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$scaleHeldItem(LivingEntity entity, ItemStack stack,
                                                  ItemDisplayContext context, PoseStack poseStack,
                                                  SubmitNodeCollector collector, int light,
                                                  CallbackInfo ci) {
        var settings = ConfigManager.getInstance().get().animationScaling;
        if (!settings.itemViewMode.firstPerson()) {
            return; // transform is configured for third person only
        }
        int percent = settings.itemSize;
        if (percent <= 0) {
            ci.cancel(); // 0% – the held item is invisible
            return;
        }
        // Which hand this render is for: the display context names the physical side, and the
        // player's own main-arm option decides which side is the main hand.
        boolean mainHand = (context == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND)
                == (entity.getMainArm() == HumanoidArm.RIGHT);
        int offsetX = settings.handOffsetX(mainHand);
        int offsetY = settings.handOffsetY(mainHand);
        int offsetZ = settings.handOffsetZ(mainHand);
        if (offsetX != 0 || offsetY != 0 || offsetZ != 0) {
            poseStack.translate(offsetX / 100.0F, offsetY / 100.0F, offsetZ / 100.0F);
        }
        if (percent != 100) {
            float scale = Math.min(percent, 1000) / 100.0F;
            poseStack.scale(scale, scale, scale);
        }
    }
}
