/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.ui.theme.ScreenOpacity;

/**
 * Per-screen opacity: before any screen draws, the palette is set to that screen's own value (or the
 * global one); after it has drawn, the short "changed" notice goes on top. Read-only for the screen -
 * nothing it draws is skipped or moved.
 */
@Mixin(Screen.class)
public abstract class ScreenOpacityMixin {

    @Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("HEAD"))
    private void sbs$screenOpacity(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick,
                                   CallbackInfo ci) {
        ScreenOpacity.enter((Screen) (Object) this);
    }

    @Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("TAIL"))
    private void sbs$screenOpacityNotice(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick,
                                         CallbackInfo ci) {
        ScreenOpacity.drawNotice(g);
    }
}
