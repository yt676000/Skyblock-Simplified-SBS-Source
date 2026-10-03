/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.core.input.MouseKeeper;

/**
 * "Keep Mouse Position": the cursor stays where it was across menu switches.
 *
 * <p>Vanilla centres the cursor on every menu open that follows a close (close → grab → open, which
 * is every click in a Hypixel menu that swaps the container). Feeding the kept position into the
 * un-grab is NOT enough: the coordinates passed there only reach the grabbed-mode virtual cursor,
 * and the real one comes out of the mode switch wherever GLFW decides to put it. So this attacks
 * the symptom instead of the transition, in three layers:
 *
 * <ol>
 *   <li>{@code releaseMouse} still un-grabs straight to the kept position (harmless at worst, and
 *       on stacks where the passed position does land it avoids even one centred frame) – and arms
 *       the {@link MouseKeeper} teleport guard on every call.</li>
 *   <li>Every {@code onMove} that reports the exact window centre while the guard is armed is the
 *       transition's teleport: the event is swallowed – it reaches neither the screen nor the kept
 *       position, which the old version let be overwritten with the centre – and the real cursor is
 *       put back where it belongs.</li>
 *   <li>Once per frame ({@code handleAccumulatedMovement}) the REAL cursor position is read back
 *       and corrected the same way, because the transition's own cursor moves are self-inflicted
 *       and GLFW does not always report those through the callback at all.</li>
 * </ol>
 */
@Mixin(MouseHandler.class)
public abstract class KeepMousePositionMixin {

    @Shadow
    private double xpos;

    @Shadow
    private double ypos;

    @Shadow
    private boolean mouseGrabbed;

    /**
     * Swallow the centre teleport; track every other move. The teleport must not reach vanilla
     * either: it would go into {@code xpos}/{@code ypos} (where the menu reads hover from) and into
     * the accumulated deltas.
     */
    @Inject(method = "onMove", at = @At("HEAD"), cancellable = true)
    private void sbs$guardMove(long windowHandle, double x, double y, CallbackInfo ci) {
        if (!this.mouseGrabbed) {
            String reason = MouseKeeper.teleportReason(x, y);
            if (reason != null) {
                snapBack(windowHandle, reason);
                ci.cancel();
                return;
            }
        }
        MouseKeeper.track(x, y, this.mouseGrabbed);
    }

    /**
     * Un-grab to the kept position instead of the centre, and arm the teleport guard. The guard is
     * armed on EVERY call – vanilla runs {@code releaseMouse} on each menu open, grabbed or not, so
     * this is exactly "a menu is opening" – while the replacement below only applies to the actual
     * grabbed release (vanilla's own guard makes the rest a no-op anyway).
     */
    @Inject(method = "releaseMouse", at = @At("HEAD"), cancellable = true)
    private void sbs$releaseToKeptPosition(CallbackInfo ci) {
        var window = Minecraft.getInstance().getWindow();
        MouseKeeper.armGuard(window.getScreenWidth() / 2, window.getScreenHeight() / 2);
        if (!this.mouseGrabbed || !MouseKeeper.hasKept()) {
            return;
        }
        this.mouseGrabbed = false;
        this.xpos = MouseKeeper.keptX();
        this.ypos = MouseKeeper.keptY();
        InputConstants.grabOrReleaseMouse(window, InputConstants.CURSOR_NORMAL,
                MouseKeeper.keptX(), MouseKeeper.keptY());
        ci.cancel();
    }

    /**
     * The per-frame backstop: read where the cursor REALLY is and un-centre it. The transition's
     * own cursor moves are set programmatically, and GLFW suppresses its callback for those – so a
     * centred cursor can sit there without {@code onMove} ever having said so.
     */
    @Inject(method = "handleAccumulatedMovement", at = @At("HEAD"))
    private void sbs$guardFrame(CallbackInfo ci) {
        if (this.mouseGrabbed) {
            return;
        }
        // Report a window that closed without correcting anything BEFORE the guarding() gate: by
        // then the window is over, so a check placed after it could never run. The absence of a
        // snap-back is the bug's whole footprint, and this is the only place it can be noticed.
        if (MouseKeeper.pendingMiss()) {
            long handle = Minecraft.getInstance().getWindow().handle();
            double[] mx = new double[1];
            double[] my = new double[1];
            GLFW.glfwGetCursorPos(handle, mx, my);
            sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Mouse] Menu opened and the cursor was never corrected. kept={},{} "
                            + "expected centre={},{} cursor now={},{} (distance from kept {})",
                    Math.round(MouseKeeper.keptX()), Math.round(MouseKeeper.keptY()),
                    Math.round(MouseKeeper.guardCenterX()), Math.round(MouseKeeper.guardCenterY()),
                    Math.round(mx[0]), Math.round(my[0]),
                    Math.round(Math.hypot(mx[0] - MouseKeeper.keptX(), my[0] - MouseKeeper.keptY())));
        }
        if (!MouseKeeper.guarding()) {
            return;   // outside the post-open window - no native read on the ordinary frame
        }
        long handle = Minecraft.getInstance().getWindow().handle();
        double[] cx = new double[1];
        double[] cy = new double[1];
        GLFW.glfwGetCursorPos(handle, cx, cy);
        String reason = MouseKeeper.teleportReason(cx[0], cy[0]);
        if (reason != null) {
            snapBack(handle, reason);
        }
    }

    /**
     * Puts the real cursor and the game's idea of it back on the kept position, once.
     *
     * <p><b>Refuses outright while the mouse is grabbed.</b> Both callers already check, so this is
     * belt and braces - but it is the check that matters most, because in grabbed mode the OS cursor
     * is hidden and GLFW turns a {@code glfwSetCursorPos} into look movement. A snap-back that
     * escaped into mouselook would not nudge a cursor, it would swing the camera by the distance
     * between the kept position and the centre, which on a 1080p screen is most of a full turn. The
     * mod is not allowed to move the player's view under any circumstances, so the prohibition lives
     * here, at the one call that could do it, rather than only at the sites that happen to call it.
     *
     * <p>Disarms the guard, so the correction happens once per menu open and cannot fight a hand
     * that legitimately moves through the middle of the screen.
     */
    private void snapBack(long windowHandle, String reason) {
        if (this.mouseGrabbed) {
            return;
        }
        MouseKeeper.consumeGuard();
        this.xpos = MouseKeeper.keptX();
        this.ypos = MouseKeeper.keptY();
        GLFW.glfwSetCursorPos(windowHandle, MouseKeeper.keptX(), MouseKeeper.keptY());
        // Stays at debug: this is the feature working, once per menu open, and a Hypixel menu opens
        // on every click. The line worth having at info is the one in sbs$guardFrame, for the case
        // where this never ran.
        sbs.modid.SkyblockSimplifiedSBS.LOGGER.debug(
                "[SBS][Mouse] Un-centred the cursor back to {}, {} after a menu transition ({}).",
                MouseKeeper.keptX(), MouseKeeper.keptY(), reason);
    }
}
