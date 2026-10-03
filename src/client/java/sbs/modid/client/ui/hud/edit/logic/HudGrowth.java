/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.hud.edit.logic;

import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.model.HudTransform;

/**
 * Decides which way a HUD element is allowed to <b>grow</b>.
 *
 * <p>Almost every SBS card sizes itself to its content ({@link HudLayout#measure}): a slayer tracker
 * gains a row per drop, the timer card a row per event, the carry counter a row per carry. All of them
 * draw from their top-left corner, so growth has always gone right and down – straight off the screen
 * for anything parked against the right or bottom border. This class produces the correction that
 * sends the extra size somewhere it can actually be read.
 *
 * <p><b>The rule is the whole feature: grow towards the middle of the screen.</b> Per axis, the
 * element's own position picks the pinned edge – a card in the right half keeps its right edge and
 * widens leftwards, one in the bottom half keeps its bottom edge and grows upwards, one sitting
 * exactly on a screen centre line grows evenly to both sides. The result is then clamped back on
 * screen, never tighter than the element's anchor already sits.
 *
 * <p><b>Nothing moves unless it actually grew.</b> The correction is per axis and only applies where
 * the measured content is bigger than the element's designed box, so an element at or under its
 * default size renders exactly where it always did.
 *
 * <p><b>Nothing here looks at the other elements.</b> An earlier version also stepped a grown card
 * clear of whatever it now covered, which made every card's position depend on its neighbours'
 * corrected positions – re-solved on a timer, so cards visibly shuffled around each other and never
 * settled. The correction each element gets is now a pure function of its own anchor, its own
 * measured size and the screen, recomputed on the spot: it cannot oscillate, and it cannot lag behind
 * the content it is correcting for. Two cards the player parked on top of each other stay on top of
 * each other, which is what parking them there asked for.
 */
public final class HudGrowth {

    /** Returned for the overwhelming majority of elements: no growth, no correction. */
    private static final float[] NONE = {0f, 0f};

    /** How far from the screen border grown content is kept, when it has to be pulled back in. */
    private static final int MARGIN = 2;

    /**
     * How far off a screen centre line an element may sit and still count as perfectly centred, in
     * GUI pixels. Not a band – just slack for the rounding in a "centred" default position.
     */
    private static final float CENTRE_TOLERANCE = 1.0f;

    /** Scratch for {@link #offset}, so a per-frame, per-element call allocates nothing. */
    private static final ThreadLocal<float[]> scratch = ThreadLocal.withInitial(() -> new float[2]);

    private HudGrowth() {
    }

    /**
     * The correction for {@code element}, in its own pre-scale coordinate space, as {@code {dx, dy}}.
     *
     * <p>Both {@link HudLayout#begin} and {@link HudLayout#displayBounds} apply it, so the rendered
     * element and the editor box it is dragged by stay the same rectangle.
     *
     * <p>The returned array is reused – read it, do not keep it.
     */
    public static float[] offset(HudElement element, int guiWidth, int guiHeight) {
        HudTransform t = HudLayout.get(element);
        HudElement.Bounds def = element.defaultBounds(guiWidth, guiHeight);
        HudElement.Bounds local = HudLayout.localBounds(element, guiWidth, guiHeight);
        float s = (float) t.scale;
        float anchorW = def.w() * s;
        float anchorH = def.h() * s;
        float grownW = local.w() * s;
        float grownH = local.h() * s;
        boolean wider = grownW > anchorW + 0.5f;
        boolean taller = grownH > anchorH + 0.5f;
        if (!wider && !taller) {
            return NONE;
        }
        // The anchor is the box the user placed; the natural box is what the element draws today,
        // growing right and down from that same top-left corner.
        float anchorX = (float) (def.x() + t.x);
        float anchorY = (float) (def.y() + t.y);
        float naturalX = anchorX + (local.x() - def.x()) * s;
        float naturalY = anchorY + (local.y() - def.y()) * s;
        // The ring the card paints around itself counts as part of it: clamping the measured box to
        // the border would push the ring off the edge, where it is simply cut off.
        float bleed = HudLayout.VISUAL_BLEED * s;
        float[] out = scratch.get();
        out[0] = wider
                ? (pin(naturalX, grownW, anchorX, anchorW, bleed, guiWidth) - naturalX) / s
                : 0f;
        out[1] = taller
                ? (pin(naturalY, grownH, anchorY, anchorH, bleed, guiHeight) - naturalY) / s
                : 0f;
        return out;
    }

    /**
     * Where the grown box starts on one axis: pinned to the edge that faces away from the middle of
     * the screen, then clamped back on screen.
     *
     * @param natural    where the box starts today, growing from the top-left corner
     * @param size       the grown size along this axis
     * @param anchor     where the element's designed box starts
     * @param anchorSize the designed size along this axis
     * @param bleed      how far outside itself the element paints its ring
     * @param span       the screen size along this axis
     */
    private static float pin(float natural, float size, float anchor, float anchorSize, float bleed,
                             int span) {
        float centre = anchor + anchorSize / 2;
        float screenCentre = span / 2f;
        float start;
        if (Math.abs(centre - screenCentre) <= CENTRE_TOLERANCE) {
            start = centre - size / 2;          // dead centre: half the growth each way
        } else if (centre > screenCentre) {
            start = anchor + anchorSize - size; // far half: keep the outer edge, grow inwards
        } else {
            start = natural;                    // near half: growing outwards already heads inwards
        }
        // Never clamp tighter than the anchor already is - an element deliberately parked half off
        // screen stays there, and only the part growth added gets pulled back.
        float min = Math.min(anchor, MARGIN + bleed);
        float max = Math.max(anchor + anchorSize, span - MARGIN - bleed);
        if (start + size > max) {
            start = max - size;
        }
        return Math.max(start, min);
    }
}
