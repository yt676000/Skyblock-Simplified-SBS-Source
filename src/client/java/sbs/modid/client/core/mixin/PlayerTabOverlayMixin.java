/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.helper.tablist.TabListRenderer;

/**
 * The tab overlay's two SBS hooks.
 *
 * <ul>
 *   <li><b>The SBS Tab-List reskin</b> – {@code extractRenderState} is the overlay's whole draw call,
 *       and the Hud only invokes it while the player-list key is held, so taking over here inherits
 *       the vanilla show/hide behaviour for free. When the module is on the vanilla render is
 *       swallowed and {@link TabListRenderer} paints the same content as a themed SBS panel.</li>
 *   <li><b>Hide Ping</b> – {@code extractPingIcon} is the one method that draws a row's connection
 *       bars, so cancelling it drops them and leaves every other thing about the row alone. This is
 *       the <b>vanilla</b> path: with the reskin on, the call above has already been cancelled and
 *       this never runs, which is why {@link TabListRenderer} honours the same setting itself.</li>
 * </ul>
 */
@Mixin(PlayerTabOverlay.class)
public abstract class PlayerTabOverlayMixin {

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$sbsTabList(GuiGraphicsExtractor g, int guiWidth,
                                               Scoreboard scoreboard, Objective objective,
                                               CallbackInfo ci) {
        if (TabListRenderer.active()) {
            TabListRenderer.render((PlayerTabOverlay) (Object) this, g, guiWidth, scoreboard, objective);
            ci.cancel();
        }
    }

    @Inject(method = "extractPingIcon", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$hidePing(GuiGraphicsExtractor g, int width, int x, int y,
                                             PlayerInfo info, CallbackInfo ci) {
        if (TabListRenderer.pingHidden()) {
            ci.cancel();
        }
    }
}
