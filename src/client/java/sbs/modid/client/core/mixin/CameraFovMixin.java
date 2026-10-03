/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.helper.etherwarp.EtherWarpZoom;

/**
 * Applies the Ether Warp zoom to the camera's field of view.
 *
 * <p>{@code Camera.calculateFov} is the single place the per-frame FOV is produced (called from
 * {@code Camera.update} and stored in the {@code fov} field every other renderer reads), so
 * rewriting its return value zooms the view without touching the player's FOV option or any other
 * camera state.
 *
 * <p>{@link EtherWarpZoom#apply} returns the value unchanged unless the zoom is enabled AND the
 * player is sneaking with an etherwarp-capable AOTE/AOTV, so normal gameplay is unaffected.
 */
@Mixin(Camera.class)
public abstract class CameraFovMixin {

    @Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
    private void skyblockSimplified$etherWarpZoom(float partialTick, CallbackInfoReturnable<Float> cir) {
        // Plain hold-to-zoom first, then the Ether Warp zoom (both narrow; strongest wins naturally
        // because they multiply the same value in sequence only when active).
        float zoomed = sbs.modid.client.helper.visual.logic.ZoomControl.apply(cir.getReturnValueF());
        zoomed = EtherWarpZoom.apply(zoomed);
        if (zoomed != cir.getReturnValueF()) {
            cir.setReturnValue(zoomed);
        }
    }
}
