/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */
package sbs.modid.client.core.mixin;

import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.helper.loadouts.PreviewEntities;

/**
 * GUI preview models never show a name. The Loadouts preview is a {@code RemotePlayer} with your
 * profile that is never added to the level, so {@link AvatarRenderer#shouldShowName} treats it as
 * another player and, whenever its checks pass, draws your full nametag across the card. Returning
 * {@code false} here leaves the render state's {@code nameTag} null, which is also what keeps the
 * badge / note / streamer mixins (all gated on a non-null tag) off it. Real players are untouched.
 */
@Mixin(AvatarRenderer.class)
public abstract class PreviewNametagMixin {

    @Inject(method = "shouldShowName(Lnet/minecraft/world/entity/Avatar;D)Z",
            at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$hidePreviewName(Avatar entity, double distanceSq,
                                                    CallbackInfoReturnable<Boolean> cir) {
        if (PreviewEntities.isPreview(entity)) {
            cir.setReturnValue(false);
        }
    }
}
