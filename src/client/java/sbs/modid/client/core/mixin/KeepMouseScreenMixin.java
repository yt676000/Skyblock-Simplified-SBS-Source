/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */
package sbs.modid.client.core.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.core.dev.DevMode;
import sbs.modid.client.core.input.MouseKeeper;

/** "Keep Mouse Position" diagnostics: which screen each {@code Gui.setScreen} installs, dev mode only. */
@Mixin(Gui.class)
public abstract class KeepMouseScreenMixin {

    @Inject(method = "setScreen", at = @At("HEAD"))
    private void sbs$logScreen(Screen screen, CallbackInfo ci) {
        if (!DevMode.ACTIVE) { // DEV-ONLY: diagnostics for Keep Mouse Position
            return;
        }
        var mouse = Minecraft.getInstance().mouseHandler;
        MouseKeeper.debug("screen", mouse.xpos(), mouse.ypos(),
                screen == null ? "null" : screen.getClass().getSimpleName());
    }
}
