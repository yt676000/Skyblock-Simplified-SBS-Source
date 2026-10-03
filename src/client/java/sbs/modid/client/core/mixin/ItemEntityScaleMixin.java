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
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

/**
 * The Animation &amp; Scaling module's <b>Dropped Item Scale</b>: uniform render scale (10–1000%,
 * 100% = vanilla) for items lying on the ground, behind its own toggle.
 *
 * <p>Injects right after the renderer's own {@code pushPose()} in {@code submit}, so the scale
 * lives inside the vanilla push/pop and composes with everything that follows (hover bounce,
 * spin rotation, stack-count sub-copies). The pose origin at that point is the entity's ground
 * anchor, so items grow upward from where they lie instead of clipping into the floor.
 */
@Mixin(ItemEntityRenderer.class)
public abstract class ItemEntityScaleMixin {

    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At(value = "INVOKE", shift = At.Shift.AFTER,
                    target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V"))
    private void skyblockSimplified$scaleDroppedItem(ItemEntityRenderState state, PoseStack poseStack,
                                                     SubmitNodeCollector collector, CameraRenderState camera,
                                                     CallbackInfo ci) {
        SBSConfig.AnimationScalingSettings settings = ConfigManager.getInstance().get().animationScaling;
        if (!settings.droppedItemScaleEnabled) {
            return;
        }
        float factor = Math.max(10, Math.min(settings.droppedItemScale, 1000)) / 100.0F;
        if (factor != 1.0F) {
            poseStack.scale(factor, factor, factor);
        }
    }
}
