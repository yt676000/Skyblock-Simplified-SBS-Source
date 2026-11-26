/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The camera shake vanilla folds into the level's projection matrix in
 * {@code GameRenderer.renderLevel} (verified on the 26.2 jar): damage tilt ({@code bobHurt}), view
 * bobbing ({@code bobView}) and the nausea / portal warp driven by the two spinning-effect fields.
 * {@code Camera.getViewRotationProjectionMatrix} has none of it, so {@code FrameCamera} rebuilds the
 * level's matrix through these - calling vanilla's own methods keeps it identical, including the
 * damage-tilt strength option.
 */
@Mixin(GameRenderer.class)
public interface GameRendererBobAccessor {

    @Invoker("bobHurt")
    void skyblockSimplified$bobHurt(CameraRenderState cameraState, PoseStack poseStack);

    @Invoker("bobView")
    void skyblockSimplified$bobView(CameraRenderState cameraState, PoseStack poseStack);

    @Accessor("spinningEffectTime")
    float skyblockSimplified$spinningEffectTime();

    @Accessor("spinningEffectSpeed")
    float skyblockSimplified$spinningEffectSpeed();
}
