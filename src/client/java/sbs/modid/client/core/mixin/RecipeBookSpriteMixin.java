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
import sbs.modid.client.ui.theme.RecipeBookSkin;

/**
 * Minecraft Overlay module: the draw calls {@link RecipeBookSkin} swaps recipe-book sprites at.
 *
 * <p>Every part of the book goes through one of these two overloads (javap-confirmed, 26.2): the
 * backdrop through the ten-argument {@code blit}, and the tabs, tiles, page arrows, filter toggle and
 * search field through {@code blitSprite(pipeline, sprite, x, y, w, h)}. Both are hot paths - every
 * GUI sprite passes here - so the first thing checked is a single static flag that is false except
 * while a themed recipe book is drawing; only then is the sprite looked up.
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class RecipeBookSpriteMixin {

    @Inject(method = "blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;"
            + "Lnet/minecraft/resources/Identifier;IIII)V", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$recipeBookSprite(RenderPipeline pipeline, Identifier sprite, int x, int y,
                                                     int width, int height, CallbackInfo ci) {
        if (RecipeBookSkin.draw((GuiGraphicsExtractor) (Object) this, sprite, x, y, width, height)) {
            ci.cancel();
        }
    }

    @Inject(method = "blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;"
            + "Lnet/minecraft/resources/Identifier;IIFFIIII)V", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$recipeBookTexture(RenderPipeline pipeline, Identifier texture, int x, int y,
                                                      float u, float v, int width, int height,
                                                      int textureWidth, int textureHeight, CallbackInfo ci) {
        if (RecipeBookSkin.draw((GuiGraphicsExtractor) (Object) this, texture, x, y, width, height)) {
            ci.cancel();
        }
    }
}
