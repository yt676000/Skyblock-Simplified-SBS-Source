/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.state.gui.ColoredRectangleRenderState;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.TiledBlitRenderState;
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.helper.overlayinspector.OverlayInspector;

/**
 * Feeds the Overlay Inspector: records the screen box of every element the client draws, together
 * with the mod that drew it.
 *
 * <p>{@code GuiRenderState} is the single place all GUI drawing converges – rectangles, sprites,
 * text, item icons and the picture-in-picture states alike – and each submission carries the box it
 * covers. Tapping it here is what lets the inspector answer for <i>every</i> overlay in the client,
 * including mods that were never written with anything like this in mind.
 *
 * <p>Glyph submissions are deliberately not recorded: they are the per-character geometry of text
 * already recorded whole through {@code addText}, so they would multiply the record count by the
 * length of every string on screen for no extra answer.
 *
 * <p>Costs nothing while the inspector is off – every hook is one volatile boolean read, and the
 * stack walk behind {@code record} only ever runs while a player is deliberately inspecting.
 */
@Mixin(GuiRenderState.class)
public class GuiRenderCaptureMixin {

    /**
     * These handlers run merged into the target class, so on the call stack they are indisting-
     * uishable from the game's own code except by their name. That name must therefore <b>not</b>
     * look like SBS to the attribution pass, or every element in the client would be answered with
     * "SkyBlock Simplified" – see {@code ModAttribution.CAPTURE_PREFIX}, which skips exactly these.
     */
    private static boolean sbsInspectorCapture$active() {
        OverlayInspector inspector = OverlayInspector.getInstance();
        return inspector.isActive() && !inspector.isDrawingSelf();
    }

    /** Opaque marker for states that carry no colour of their own (items, sub-scenes, text). */
    private static final int SBS_OPAQUE = 0xFF000000;

    /**
     * The colour a state is drawn in, so fully transparent geometry can be dropped before it is
     * recorded. Anything whose colour cannot be read counts as opaque – dropping a visible element
     * is far worse than keeping an invisible one.
     */
    private static int sbsInspectorCapture$colorOf(GuiElementRenderState state) {
        if (state instanceof ColoredRectangleRenderState rectangle) {
            return (rectangle.col1() & 0xFF000000) | (rectangle.col2() & 0xFF000000);
        }
        if (state instanceof BlitRenderState blit) {
            return blit.color();
        }
        if (state instanceof TiledBlitRenderState tiled) {
            return tiled.color();
        }
        return SBS_OPAQUE;
    }

    /**
     * Whether the recorded box actually describes the drawn pixels.
     *
     * <p>A rotated quad reports the axis-aligned box <i>around</i> itself, so a diagonal 2 px line –
     * how every world-space tracer and box outline is drawn – claims a rectangle the size of its
     * diagonal while covering almost none of it. Left in, those claims blanket the screen and answer
     * for whatever they happen to cross. Only unrotated geometry can be honestly hit-tested.
     */
    private static boolean sbsInspectorCapture$axisAligned(org.joml.Matrix3x2fc pose) {
        return pose == null || (Math.abs(pose.m01()) < 1.0e-4f && Math.abs(pose.m10()) < 1.0e-4f);
    }

    private static org.joml.Matrix3x2fc sbsInspectorCapture$poseOf(GuiElementRenderState state) {
        if (state instanceof ColoredRectangleRenderState rectangle) {
            return rectangle.pose();
        }
        if (state instanceof BlitRenderState blit) {
            return blit.pose();
        }
        if (state instanceof TiledBlitRenderState tiled) {
            return tiled.pose();
        }
        return null;
    }

    @Inject(method = "addGuiElement", at = @At("HEAD"))
    private void sbsInspectorCapture$element(GuiElementRenderState state, CallbackInfo ci) {
        if (sbsInspectorCapture$active() && state != null
                && sbsInspectorCapture$axisAligned(sbsInspectorCapture$poseOf(state))) {
            OverlayInspector.getInstance().record(state.bounds(), sbsInspectorCapture$colorOf(state));
        }
    }

    @Inject(method = "addBlitToCurrentLayer", at = @At("HEAD"))
    private void sbsInspectorCapture$blit(BlitRenderState state, CallbackInfo ci) {
        if (sbsInspectorCapture$active() && state != null
                && sbsInspectorCapture$axisAligned(state.pose())) {
            OverlayInspector.getInstance().record(state.bounds(), state.color());
        }
    }

    @Inject(method = "addText", at = @At("HEAD"))
    private void sbsInspectorCapture$text(GuiTextRenderState state, CallbackInfo ci) {
        if (sbsInspectorCapture$active() && state != null
                && sbsInspectorCapture$axisAligned(state.pose)) {
            OverlayInspector.getInstance().record(state.bounds(), SBS_OPAQUE);
        }
    }

    @Inject(method = "addItem", at = @At("HEAD"))
    private void sbsInspectorCapture$item(GuiItemRenderState state, CallbackInfo ci) {
        if (sbsInspectorCapture$active() && state != null
                && sbsInspectorCapture$axisAligned(state.pose())) {
            OverlayInspector.getInstance().record(state.bounds(), SBS_OPAQUE);
        }
    }

    @Inject(method = "addPicturesInPictureState", at = @At("HEAD"))
    private void sbsInspectorCapture$pictureInPicture(PictureInPictureRenderState state,
                                                      CallbackInfo ci) {
        if (sbsInspectorCapture$active() && state != null) {
            OverlayInspector.getInstance().record(state.bounds(), SBS_OPAQUE);
        }
    }
}
