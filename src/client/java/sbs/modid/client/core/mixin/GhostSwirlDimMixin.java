/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.renderer.entity.layers.CreeperPowerLayer;
import net.minecraft.client.renderer.entity.layers.EnergySwirlLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import sbs.modid.client.helper.visual.logic.MistVisuals;

/**
 * Dims the charged-creeper aura - in The Mist, the aura <i>is</i> the ghost: a SkyBlock ghost is an
 * invisible creeper whose charge layer is the only visible body, and vanilla draws that layer at a
 * fixed, searing brightness.
 *
 * <p>The seam: {@code EnergySwirlLayer.submit} makes exactly one {@code submitModel} call, and the
 * model colour it passes is a hard-coded constant ({@code 0xFF808080} - the modern home of the old
 * {@code renderToBuffer(..., 0.5f, 0.5f, 0.5f, 1.0f)}). Re-writing that one argument scales the
 * whole aura towards black without touching the render type, the texture scroll or anything else
 * about the layer. {@link MistVisuals#swirlColor} returns the argument unchanged on a single
 * volatile read while the feature is off or out of area.
 *
 * <p>The class check keeps this to creepers: {@code EnergySwirlLayer} is also the wither's armor
 * layer, which nobody asked to dim - a ghost slider must not change how withers look.
 */
@Mixin(EnergySwirlLayer.class)
public abstract class GhostSwirlDimMixin {

    @ModifyArg(method = "submit",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/OrderedSubmitNodeCollector;submitModel("
                            + "Lnet/minecraft/client/model/Model;Ljava/lang/Object;"
                            + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                            + "Lnet/minecraft/client/renderer/rendertype/RenderType;III"
                            + "Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;I"
                            + "Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V"),
            index = 6)
    private int skyblockSimplified$dimGhostAura(int color) {
        if ((Object) this instanceof CreeperPowerLayer) {
            return MistVisuals.swirlColor(color);
        }
        return color;
    }
}
