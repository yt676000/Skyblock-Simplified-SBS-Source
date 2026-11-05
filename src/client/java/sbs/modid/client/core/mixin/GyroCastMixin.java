/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.combat.gyro.logic.GyroState;

/**
 * A left click - the Gyrokinetic Wand's Gravity Storm. {@code ItemUseMixin} only sees right
 * clicks, and the wand's cast is the attack key, so this is the one moment the client knows a cast
 * was attempted. Observes only; nothing is cancelled or sent.
 */
@Mixin(Minecraft.class)
public abstract class GyroCastMixin {

    @Shadow
    public LocalPlayer player;

    @Inject(method = "startAttack", at = @At("HEAD"))
    private void skyblockSimplified$gyroCast(CallbackInfoReturnable<Boolean> cir) {
        if (player != null && GyroState.getInstance().holding()) {
            GyroState.getInstance().onLeftClick(player.getMainHandItem());
        }
    }
}
