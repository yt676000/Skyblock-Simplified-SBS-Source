/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.social.presence.SbsPresence;

/**
 * The SBS Players module's <b>tab-list badge</b>: prepends the SBS icon to each player's name in the
 * player list. {@link PlayerTabOverlay#getNameForDisplay} is the single point where the tab overlay
 * turns a {@link PlayerInfo} into the name it draws, so decorating its return value badges the row
 * wherever and however it is shown.
 */
@Mixin(PlayerTabOverlay.class)
public abstract class SbsTabBadgeMixin {

    @Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
    private void skyblockSimplified$badgeTabName(PlayerInfo info, CallbackInfoReturnable<Component> cir) {
        if (info != null && SbsPresence.getInstance().isSbsUser(info.getProfile().id())) {
            cir.setReturnValue(SbsPresence.badge(cir.getReturnValue()));
        }
    }
}
