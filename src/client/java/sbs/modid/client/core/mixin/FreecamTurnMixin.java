/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.helper.build.logic.Freecam;

/**
 * While freecam is on, mouse look turns the camera and not the player: the local player's
 * {@code turn} (the call {@code MouseHandler.turnPlayer} makes) is handed to {@link Freecam} and
 * skipped, so the player's rotation - and every rotation the client reports - stays where it was.
 */
@Mixin(Entity.class)
public abstract class FreecamTurnMixin {

    @Inject(method = "turn", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$freecamTurn(double dx, double dy, CallbackInfo ci) {
        if (Freecam.active() && (Object) this == Minecraft.getInstance().player) {
            Freecam.turn(dx, dy);
            ci.cancel();
        }
    }
}
