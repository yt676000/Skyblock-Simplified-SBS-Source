/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.helper.overlayinspector.OverlayInspector;

/**
 * The Overlay Inspector's pointer: turns mouse movement into a cursor instead of a camera turn, and
 * keeps clicks out of the world while the pointer is up.
 *
 * <p>{@code turnPlayer} is the one method that applies accumulated mouse movement to the view, so
 * taking the accumulated deltas there and cancelling gives the inspector a pointer that moves
 * exactly like the system cursor would – without opening a screen, and therefore without changing
 * the HUD it exists to inspect.
 *
 * <p>The deltas are zeroed as they are consumed: left in place they would be applied in one lump the
 * moment the inspector is switched off, snapping the camera to wherever the pointer had travelled.
 *
 * <p>{@code onButton} is swallowed whole while inspecting – a click on an overlay must not also
 * swing the weapon behind it. Switching the inspector off releases every key, so a button whose
 * release was swallowed cannot stay stuck down.
 *
 * <p>Both hooks are one field read while the inspector is off.
 */
@Mixin(MouseHandler.class)
public abstract class InspectorMouseMixin {

    @Shadow
    private double accumulatedDX;

    @Shadow
    private double accumulatedDY;

    /** GLFW {@code GLFW_PRESS}. */
    private static final int SBS_PRESS = 1;

    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$inspectorPointer(double movementTime, CallbackInfo ci) {
        OverlayInspector inspector = OverlayInspector.getInstance();
        if (!inspector.isActive()) {
            return;
        }
        inspector.moveCursor(accumulatedDX, accumulatedDY);
        accumulatedDX = 0;
        accumulatedDY = 0;
        ci.cancel();
    }

    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$inspectorClick(long window, MouseButtonInfo button, int action,
                                                   CallbackInfo ci) {
        OverlayInspector inspector = OverlayInspector.getInstance();
        if (!inspector.isActive()) {
            return;
        }
        if (action == SBS_PRESS) {
            inspector.onClick(button.button());
        }
        ci.cancel();
    }
}
