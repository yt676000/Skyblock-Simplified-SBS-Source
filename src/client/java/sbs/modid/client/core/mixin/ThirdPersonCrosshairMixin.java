/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.CameraType;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import sbs.modid.client.core.config.ConfigManager;

/**
 * The Third Person module's <b>crosshair in third person</b>: vanilla only draws the crosshair while
 * {@code getCameraType().isFirstPerson()}. This redirects that one check inside
 * {@link Hud#extractCrosshair} so the crosshair is also drawn in third person while the toggle is on
 * – every other camera / spectator gate the method has is left untouched.
 */
@Mixin(Hud.class)
public abstract class ThirdPersonCrosshairMixin {

    @Redirect(method = "extractCrosshair",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/CameraType;isFirstPerson()Z"))
    private boolean skyblockSimplified$crosshairInThirdPerson(CameraType cameraType) {
        return cameraType.isFirstPerson()
                || ConfigManager.getInstance().get().thirdPerson.crosshair;
    }
}
