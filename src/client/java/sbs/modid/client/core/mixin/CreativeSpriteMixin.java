/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.ui.theme.CreativeSkin;

/**
 * Minecraft Overlay module: the draw calls {@link CreativeSkin} swaps creative-inventory sprites at -
 * the tabs and scroller through {@code blitSprite(pipeline, sprite, x, y, w, h)}, the tab background
 * through the ten-argument {@code blit} (javap, 26.2). Hot paths: the first check is a static flag
 * that is false except during the creative screen's background pass.
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class CreativeSpriteMixin {

    @Inject(method = "blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;"
            + "Lnet/minecraft/resources/Identifier;IIII)V", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$creativeSprite(RenderPipeline pipeline, Identifier sprite, int x, int y,
                                                   int width, int height, CallbackInfo ci) {
        if (CreativeSkin.draw((GuiGraphicsExtractor) (Object) this, sprite, x, y, width, height)) {
            ci.cancel();
        }
    }

    @Inject(method = "blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;"
            + "Lnet/minecraft/resources/Identifier;IIFFIIII)V", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$creativeTexture(RenderPipeline pipeline, Identifier texture, int x, int y,
                                                    float u, float v, int width, int height,
                                                    int textureWidth, int textureHeight, CallbackInfo ci) {
        if (CreativeSkin.draw((GuiGraphicsExtractor) (Object) this, texture, x, y, width, height)) {
            ci.cancel();
        }
    }
}
