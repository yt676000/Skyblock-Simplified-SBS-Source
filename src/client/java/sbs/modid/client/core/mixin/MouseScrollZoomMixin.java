/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.core.api.ScreenAccess;
import sbs.modid.client.helper.etherwarp.EtherWarpZoom;
import sbs.modid.client.helper.visual.logic.ZoomControl;

/**
 * Lets the mouse wheel fine-tune a zoom that is currently running – hold the zoom key (or aim an
 * Ether Warp) and scroll to go further in or back out.
 *
 * <p>{@code MouseHandler.onScroll} is where every wheel event enters the game, before it is split
 * into "scroll the open screen" and "switch hotbar slot". Taking it here is what makes the wheel
 * change the zoom <i>instead of</i> the held item, which is the whole point: scrolling mid-aim must
 * not swap the weapon out from under the shot.
 *
 * <p>Three conditions keep it out of the way the rest of the time. Nothing is claimed while a screen
 * is open (the wheel belongs to that screen), nothing is claimed while no zoom is running, and
 * nothing is claimed once the zoom sits at its limit – so scrolling past full zoom goes back to
 * switching hotbar slots rather than silently doing nothing.
 */
@Mixin(MouseHandler.class)
public abstract class MouseScrollZoomMixin {

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$scrollZoom(long window, double xOffset, double yOffset, CallbackInfo ci) {
        if (yOffset == 0 || ScreenAccess.current() != null) {
            return;
        }
        if (ZoomControl.scroll(yOffset) || EtherWarpZoom.scroll(yOffset)) {
            ci.cancel();
        }
    }
}
