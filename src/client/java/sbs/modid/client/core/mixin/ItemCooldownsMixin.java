/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemCooldowns;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.combat.cooldowns.CooldownTracker;

/**
 * Feeds the {@link CooldownTracker}: vanilla only exposes a cooldown <i>percentage</i> and keeps
 * its entries private, so the start/duration of every cooldown is mirrored here to compute the
 * remaining seconds for the HUD display. Observation only – nothing is cancelled or altered.
 */
@Mixin(ItemCooldowns.class)
public class ItemCooldownsMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void skyblockSimplified$tick(CallbackInfo ci) {
        CooldownTracker.getInstance().onTick();
    }

    @Inject(method = "addCooldown(Lnet/minecraft/resources/Identifier;I)V", at = @At("HEAD"))
    private void skyblockSimplified$add(Identifier group, int duration, CallbackInfo ci) {
        CooldownTracker.getInstance().onAdd(group, duration);
    }

    @Inject(method = "removeCooldown", at = @At("HEAD"))
    private void skyblockSimplified$remove(Identifier group, CallbackInfo ci) {
        CooldownTracker.getInstance().onRemove(group);
    }
}
