/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.core.config.ConfigManager;

/**
 * The Third Person module's <b>self nametag</b>: shows your own floating name above your head while
 * the camera is in third person – the same tag every other SkyBlock player already sees over you.
 *
 * <p>Vanilla never shows the local player's own name. Whether a name renders is decided by
 * {@link AvatarRenderer#shouldShowName}; the render state's {@code nameTag} (and its position
 * attachment) are then filled by the base extractor from exactly that boolean. So forcing the method
 * to return {@code true} for the local player in third person makes vanilla compute and draw the tag
 * through its normal path – no manual name/position handling, so it looks identical to other players'.
 */
@Mixin(AvatarRenderer.class)
public abstract class SelfNametagMixin {

    @Inject(method = "shouldShowName(Lnet/minecraft/world/entity/Avatar;D)Z",
            at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$showOwnNameInThirdPerson(Avatar entity, double distanceSq,
                                                             CallbackInfoReturnable<Boolean> cir) {
        if (!ConfigManager.getInstance().get().thirdPerson.selfNametag) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (entity == minecraft.player && !minecraft.options.getCameraType().isFirstPerson()) {
            cir.setReturnValue(true);
        }
    }
}
