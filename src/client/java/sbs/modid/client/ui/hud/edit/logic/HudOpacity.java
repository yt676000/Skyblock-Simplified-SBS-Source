/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.hud.edit.logic;

import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.state.gui.ColoredRectangleRenderState;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.TiledBlitRenderState;
import sbs.modid.client.ui.hud.edit.model.HudTransform;

/**
 * The alpha multipliers currently in force for HUD drawing – the opacity half of a {@link HudTransform},
 * to {@link HudLayout#begin}/{@link HudLayout#end} what the pose matrix is to position and scale.
 *
 * <p>Alpha cannot ride on the matrix stack, and making every overlay thread an alpha through its own
 * colours would mean touching all ~30 of them (and would still miss the vanilla elements, which SBS
 * does not draw at all). Instead this holds plain static multipliers that {@code begin} raises and
 * {@code end} drops, and the render pipeline is tapped once, centrally: every GUI element this version
 * of Minecraft records goes through {@code GuiRenderState}, so
 * {@code GuiHudOpacityMixin} rewrites the colour of whatever is submitted while a multiplier is
 * active. Hearts, hotbar, chat and SBS panels alike fade with no per-element code.
 *
 * <p><b>Three dials, not one.</b> Which dial a piece of geometry answers to is decided here, and it
 * is the only place that can decide it – by the time drawing reaches the render state there is no
 * element left, only rectangles:
 *
 * <ul>
 *   <li>{@link Part#OUTLINE} – recorded inside an {@code outline()} call (see {@link #beginOutline})
 *       or painted in one of the theme's border / glow colours, which is how the SBS cards draw the
 *       ring around their plate.</li>
 *   <li>{@link Part#TEXT} and {@link Part#ICON} – the element's content: its labels and its sprites.
 *       They share a dial because they are the same thing to a reader, and because the vanilla
 *       elements (hearts, hotbar, effects) are <em>nothing but</em> icons – a content dial that left
 *       them out would do nothing at all on half the HUD.</li>
 *   <li>{@link Part#BODY} – everything else, i.e. the plate.</li>
 * </ul>
 *
 * <p>Item icons ({@code GuiItemRenderState}) are the one exception, because that state carries no
 * colour at all – it is a stack and a position, handed to the item pipeline, with no channel a fade
 * could be written into. So they keep full opacity while the content dial is anywhere above zero,
 * which is the readable outcome anyway: a faded hotbar with crisp items.
 *
 * <p><b>At exactly zero they are dropped instead</b> ({@link #hidesContent}). Zero is the one value
 * where "as faded as an item can get" is not close enough – an element asked to disappear that keeps
 * its item icons has not disappeared, and unlike every alpha in between, "gone" is a state the item
 * pipeline can represent: don't submit it.
 *
 * <p>Single-threaded by construction: GUI render state is extracted on the render thread only.
 */
public final class HudOpacity {

    /** Deep enough for any realistic nesting; overflow simply stops stacking rather than throwing. */
    private static final int MAX_DEPTH = 8;

    /** One saved triple per level: body, outline, content – in that order. */
    private static final float[] STACK = new float[MAX_DEPTH * 3];
    private static int depth;

    /** The live multipliers. All 1 outside a {@link HudLayout#begin}/{@link HudLayout#end} pair. */
    private static float bodyAlpha = 1f;
    private static float outlineAlpha = 1f;
    private static float contentAlpha = 1f;

    /**
     * Non-zero while an outline primitive is being recorded. Frames are drawn as ordinary coloured
     * rectangles, so without this marker there would be no way to tell a 1 px frame from the plate
     * behind it once it reaches the render state.
     */
    private static int outlineDepth;

    /** The kinds of geometry an element is made of, as far as fading is concerned. */
    private enum Part {
        BODY, OUTLINE, ICON, TEXT
    }

    private HudOpacity() {
    }

    /**
     * Multiplies the given values onto the current alphas (so a faded element nested inside another
     * faded one fades once for each) and remembers the previous ones for {@link #pop}.
     */
    public static void push(float body, float outline, float content) {
        if (depth < MAX_DEPTH) {
            int base = depth * 3;
            STACK[base] = bodyAlpha;
            STACK[base + 1] = outlineAlpha;
            STACK[base + 2] = contentAlpha;
        }
        depth++;
        bodyAlpha *= clamp(body);
        outlineAlpha *= clamp(outline);
        contentAlpha *= clamp(content);
    }

    /** Restores the alphas {@link #push} replaced. Unbalanced calls reset to fully opaque. */
    public static void pop() {
        if (depth <= 0) {
            depth = 0;
            bodyAlpha = 1f;
            outlineAlpha = 1f;
            contentAlpha = 1f;
            return;
        }
        depth--;
        if (depth < MAX_DEPTH) {
            int base = depth * 3;
            bodyAlpha = STACK[base];
            outlineAlpha = STACK[base + 1];
            contentAlpha = STACK[base + 2];
        } else {
            bodyAlpha = 1f;
            outlineAlpha = 1f;
            contentAlpha = 1f;
        }
    }

    /** Whether anything is currently being faded – the fast path that keeps normal drawing untouched. */
    public static boolean active() {
        return bodyAlpha < 1f || outlineAlpha < 1f || contentAlpha < 1f;
    }

    /**
     * Marks the geometry recorded until {@link #endOutline()} as an element's frame rather than its
     * background. Called around {@code GuiGraphicsExtractor.outline} and the theme's glow.
     */
    public static void beginOutline() {
        outlineDepth++;
    }

    public static void endOutline() {
        if (outlineDepth > 0) {
            outlineDepth--;
        }
    }

    /** The multiplier in force for one kind of geometry. */
    private static float alphaFor(Part part) {
        return switch (part) {
            case BODY -> bodyAlpha;
            case OUTLINE -> outlineAlpha;
            case ICON, TEXT -> contentAlpha;
        };
    }

    /**
     * Whether a coloured rectangle belongs to the element's frame: either it was recorded inside an
     * {@code outline()} call, or it is painted in one of the theme's border / glow colours, which is
     * how the SBS cards draw the plate their body sits on.
     */
    private static boolean isOutline(int color) {
        return outlineDepth > 0 || sbs.modid.client.ui.theme.SBSTheme.isBorderColor(color);
    }

    /** {@code argb} with its alpha channel scaled by {@code multiplier}. */
    private static int fade(int argb, float multiplier) {
        int a = (argb >>> 24) & 0xFF;
        if (a == 0 || multiplier >= 1f) {
            return argb; // already invisible or untouched – and this keeps gradients exact
        }
        return (Math.round(a * multiplier) << 24) | (argb & 0x00FFFFFF);
    }

    /** {@code argb} with its alpha scaled by the text / icon multiplier – what the text mixin applies. */
    public static int fadeTextColor(int argb) {
        return fade(argb, contentAlpha);
    }

    /**
     * A copy of {@code state} with its colours faded, or {@code state} itself when nothing is being
     * faded or the state carries no colour (glyph and picture-in-picture states).
     *
     * <p>The render states are records with final fields, so they are rebuilt rather than mutated:
     * mutating a record's fields through reflection is undefined behaviour the JIT is free to ignore.
     */
    public static GuiElementRenderState fadeElement(GuiElementRenderState state) {
        if (!active() || state == null) {
            return state;
        }
        if (state instanceof ColoredRectangleRenderState r) {
            float multiplier = alphaFor(isOutline(r.col1()) ? Part.OUTLINE : Part.BODY);
            if (multiplier >= 1f) {
                return state;
            }
            return new ColoredRectangleRenderState(r.pipeline(), r.textureSetup(), r.pose(),
                    r.x0(), r.y0(), r.x1(), r.y1(),
                    fade(r.col1(), multiplier), fade(r.col2(), multiplier),
                    r.scissorArea(), r.bounds());
        }
        if (state instanceof BlitRenderState b) {
            return fadeBlit(b);
        }
        if (state instanceof TiledBlitRenderState t) {
            float multiplier = alphaFor(Part.ICON);
            if (multiplier >= 1f) {
                return state;
            }
            return new TiledBlitRenderState(t.pipeline(), t.textureSetup(), t.pose(),
                    t.tileWidth(), t.tileHeight(), t.x0(), t.y0(), t.x1(), t.y1(),
                    t.u0(), t.u1(), t.v0(), t.v1(), fade(t.color(), multiplier),
                    t.scissorArea(), t.bounds());
        }
        return state;
    }

    /** {@link #fadeElement} for the blit-only submission path. */
    public static BlitRenderState fadeBlit(BlitRenderState b) {
        if (!active() || b == null) {
            return b;
        }
        float multiplier = alphaFor(Part.ICON);
        if (multiplier >= 1f) {
            return b;
        }
        return new BlitRenderState(b.pipeline(), b.textureSetup(), b.pose(),
                b.x0(), b.y0(), b.x1(), b.y1(),
                b.u0(), b.u1(), b.v0(), b.v1(), fade(b.color(), multiplier),
                b.scissorArea(), b.bounds());
    }

    /** Whether text is being faded at all – asked by the mixin before it edits a text state. */
    public static boolean fadesText() {
        return contentAlpha < 1f;
    }

    /**
     * Whether the content dial is all the way down, i.e. text and icons are to be invisible rather
     * than merely faint. Only the item path asks: everything else expresses this by fading to an
     * alpha of 0, which draws nothing without anyone having to special-case it.
     */
    public static boolean hidesContent() {
        return contentAlpha <= 0f;
    }

    /**
     * Whether the body dial is all the way down, i.e. the element's plate is to be gone rather than
     * faint.
     *
     * <p>Asked at <em>draw</em> time, before the geometry exists, and that is the whole point. An SBS
     * surface is a border-coloured plate with its body painted one pixel inside it, so the frame the
     * player sees is the plate's rim rather than a shape of its own. Fading the body to nothing
     * therefore does not leave an outline behind – it leaves the plate, which {@link #isOutline}
     * quite correctly reads as frame geometry and quite correctly declines to fade. Nothing this
     * class can do downstream recovers from that: by then the rectangle is the only thing left, and
     * a ring is not a rectangle. The surface has to be drawn as a ring in the first place, and only
     * the caller still holds the width, height and radius that takes.
     *
     * @see sbs.modid.client.ui.render.SciFiRender#ring
     */
    public static boolean hidesBody() {
        return bodyAlpha <= 0f;
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
