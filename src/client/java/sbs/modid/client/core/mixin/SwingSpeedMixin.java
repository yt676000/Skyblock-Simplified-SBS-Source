/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.core.config.ConfigManager;

/**
 * The Animation &amp; Scaling module's <b>swing speed</b>: scales the local player's swing (attack)
 * animation duration by the configured percentage. {@code getCurrentSwingDuration} drives
 * {@code updateSwingTime} / {@code attackAnim} on the client, so stretching or shrinking its
 * return value directly changes how fast the arm swings – 100% is vanilla, 10% swings ten times
 * slower, 999999% is near-instant and 0% freezes the animation entirely.
 *
 * <p>Client-side visual only (the mixin runs on the render tick path of the client player; hit
 * timing and attack cooldowns are untouched).
 */
@Mixin(LivingEntity.class)
public abstract class SwingSpeedMixin {

    @Inject(method = "getCurrentSwingDuration", at = @At("RETURN"), cancellable = true)
    private void skyblockSimplified$scaleSwingDuration(CallbackInfoReturnable<Integer> cir) {
        int percent = ConfigManager.getInstance().get().animationScaling.swingSpeed;
        if (percent == 100 || (Object) this != Minecraft.getInstance().player) {
            return;
        }
        if (percent <= 0) {
            cir.setReturnValue(Integer.MAX_VALUE); // 0% – the swing never advances
            return;
        }
        int base = cir.getReturnValueI();
        cir.setReturnValue(Math.max(1, (int) Math.round(base * 100.0 / percent)));
    }
}
