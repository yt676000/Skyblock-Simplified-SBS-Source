/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.titlescreen;

import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.ui.vector.SvgPath;
import sbs.modid.client.ui.vector.VectorCanvas;
import sbs.modid.client.ui.vector.VectorFont;
import sbs.modid.client.ui.vector.VectorImage;
import sbs.modid.client.ui.vector.VectorPaint;

/**
 * The SBS brand banner, drawn as vector artwork instead of shipped as a texture.
 *
 * <p>Two separate images, because the title menu sits between them: the {@link #MARK} goes above
 * the buttons, the {@link #WORDMARK} below (see {@link TitleBranding}). Both are baked at whatever
 * pixel size they end up being drawn at, so they stay sharp from GUI scale 1 on a small window all
 * the way to a 4K screen – which is the whole reason this is geometry and not a PNG.
 *
 * <h2>The mark</h2>
 * "SBS" is built the way the logo is: three seven-segment letterforms – thick bars, thin slots –
 * sheared to the right about their vertical centre. The {@code S} is a single contour tracing the
 * five segments of a digital {@code 5}; the {@code B} is a solid block with two counters wound
 * against it so the non-zero fill rule cuts them out. The two free bar ends (top-right of the
 * {@code S}, bottom-left of the {@code S}) are cut on a slant, which is what gives the letters
 * their forward lean beyond the shear itself.
 *
 * <h2>The wordmark</h2>
 * "SKYBLOCK" over a rule-flanked "SIMPLIFIED", set in {@link VectorFont}. The rules fade out
 * towards the screen edges via a gradient paint rather than being drawn as several segments.
 *
 * <p>Every colour comes from {@link SBSTheme}, so the Theme module recolours the title screen along
 * with the rest of the mod; {@link #themeStamp()} is what tells {@link VectorImage} to re-bake when
 * that happens.
 */
public final class SbsBrandArt {

    private SbsBrandArt() {
    }

    // ------------------------------------------------------------------
    // The mark: viewBox 1000 x 308, three 250-wide letters on a 290 step.
    // ------------------------------------------------------------------

    private static final float MARK_VIEW_W = 1000F;
    private static final float MARK_VIEW_H = 308F;
    /** Vertical inset so the anti-aliased top and bottom edges are never clipped by the buffer. */
    private static final float MARK_PAD_Y = 4F;
    private static final float GLYPH_W = 250F;
    private static final float GLYPH_H = 300F;
    private static final float GLYPH_STEP = 290F;
    /** Italic slant, as x offset per unit of height. */
    private static final float SLANT = 0.26F;

    /**
     * Seven-segment {@code S}: top bar, upper-left stem, middle bar, lower-right stem, bottom bar,
     * traced as one outline. Bars are 70 thick, the slots between them 45, and the two free ends are
     * cut back by 34 so they lean with the shear.
     */
    private static final SvgPath MARK_S = SvgPath.of(
            "M0 0 L250 0 L216 70 L70 70 L70 115 L250 115 L250 300 L0 300 "
                    + "L34 230 L180 230 L180 185 L0 185 Z");

    /**
     * Seven-segment {@code B}: a solid block with two counters. The counters are traced the other way
     * round from the block, which is what makes the non-zero fill rule treat them as holes.
     */
    private static final SvgPath MARK_B = SvgPath.of(
            "M0 0 L250 0 L250 300 L0 300 Z "
                    + "M70 70 L70 115 L190 115 L190 70 Z "
                    + "M70 185 L70 230 L190 230 L190 185 Z");

    public static final VectorImage MARK =
            new VectorImage("sbs_title_mark", MARK_VIEW_W, MARK_VIEW_H, SbsBrandArt::paintMark);

    private static void paintMark(VectorCanvas canvas) {
        VectorPaint ink = VectorPaint.solid(SBSTheme.TEXT);
        float content = 2 * GLYPH_STEP + GLYPH_W;             // 830: three letters and two gaps
        float spread = SLANT * GLYPH_H * 0.5F;                // how far the shear pushes each end out
        // The shear is symmetric about the vertical centre, so it widens the block by `spread` on
        // BOTH sides and cancels out of the centring: the untransformed content just goes in the
        // middle of the viewBox. Subtracting the spread here is what pushed the mark off-centre.
        float left = (MARK_VIEW_W - content) * 0.5F;

        canvas.push();
        canvas.translate(left, MARK_PAD_Y);
        // Shear about the vertical centre (x' = x + SLANT * (H/2 - y)) so the letters lean without
        // drifting sideways: the top edge moves right exactly as far as the bottom edge moves left,
        // and the sheared block still sits centred in the viewBox.
        canvas.translate(spread, 0F);
        canvas.shearX(-SLANT);

        SvgPath[] letters = {MARK_S, MARK_B, MARK_S};
        for (int i = 0; i < letters.length; i++) {
            canvas.push();
            canvas.translate(i * GLYPH_STEP, 0F);
            canvas.fill(letters[i], ink);
            canvas.pop();
        }
        canvas.pop();
    }

    // ------------------------------------------------------------------
    // The wordmark: viewBox 1000 x 110.
    // ------------------------------------------------------------------

    /** The grid the wordmark is laid out on; both lines are centred on its middle. */
    private static final float WORD_DESIGN_W = 1000F;

    private static final String LINE_ONE = "SKYBLOCK";
    private static final float LINE_ONE_CAP = 39F;
    private static final float LINE_ONE_TRACKING = 31.5F;
    private static final float LINE_ONE_Y = 15F;
    private static final float LINE_ONE_STROKE = 4.3F;

    private static final String LINE_TWO = "SIMPLIFIED";
    private static final float LINE_TWO_CAP = 24F;
    private static final float LINE_TWO_TRACKING = 9.7F;
    private static final float LINE_TWO_Y = 73F;
    private static final float LINE_TWO_STROKE = 2.8F;

    /** The rules flanking "SIMPLIFIED": centred on the word's own middle, fading out at the ends. */
    private static final float RULE_Y = LINE_TWO_Y + LINE_TWO_CAP * 0.5F;
    private static final float RULE_THICKNESS = 3F;
    private static final float RULE_OUTER = 278F;
    private static final float RULE_GAP = 20F;

    /**
     * The viewBox is the design grid <b>cropped to the ink</b> - the rules are its left and right
     * edges, the lettering its top and bottom. That matters because {@link TitleBranding} sizes the
     * artwork by its box: an image with wide empty margins would be scaled to the margins and draw
     * the lettering far too small.
     */
    private static final float WORD_CROP_X = RULE_OUTER;
    private static final float WORD_CROP_Y = 11F;
    private static final float WORD_VIEW_W = WORD_DESIGN_W - 2 * RULE_OUTER;
    private static final float WORD_VIEW_H = 89F;

    public static final VectorImage WORDMARK =
            new VectorImage("sbs_title_wordmark", WORD_VIEW_W, WORD_VIEW_H, SbsBrandArt::paintWordmark);

    private static void paintWordmark(VectorCanvas canvas) {
        VectorPaint accent = VectorPaint.solid(SBSTheme.ACCENT);
        // Everything below is written in design coordinates; this shifts the crop into the viewBox.
        canvas.translate(-WORD_CROP_X, -WORD_CROP_Y);

        float topWidth = VectorFont.width(LINE_ONE, LINE_ONE_CAP, LINE_ONE_TRACKING);
        VectorFont.draw(canvas, LINE_ONE, (WORD_DESIGN_W - topWidth) * 0.5F, LINE_ONE_Y,
                LINE_ONE_CAP, LINE_ONE_TRACKING, LINE_ONE_STROKE, accent);

        float bottomWidth = VectorFont.width(LINE_TWO, LINE_TWO_CAP, LINE_TWO_TRACKING);
        float bottomLeft = (WORD_DESIGN_W - bottomWidth) * 0.5F;
        VectorFont.draw(canvas, LINE_TWO, bottomLeft, LINE_TWO_Y,
                LINE_TWO_CAP, LINE_TWO_TRACKING, LINE_TWO_STROKE, accent);

        int faded = VectorPaint.withAlpha(SBSTheme.ACCENT, 0F);
        float ruleTop = RULE_Y - RULE_THICKNESS * 0.5F;
        float leftEnd = bottomLeft - RULE_GAP;
        canvas.fillRect(RULE_OUTER, ruleTop, leftEnd - RULE_OUTER, RULE_THICKNESS,
                canvas.gradientX(RULE_OUTER, faded, leftEnd, SBSTheme.ACCENT));

        float rightStart = bottomLeft + bottomWidth + RULE_GAP;
        float rightEnd = WORD_DESIGN_W - RULE_OUTER;
        canvas.fillRect(rightStart, ruleTop, rightEnd - rightStart, RULE_THICKNESS,
                canvas.gradientX(rightStart, SBSTheme.ACCENT, rightEnd, faded));
    }

    // ------------------------------------------------------------------

    /** Changes whenever a colour the artwork uses changes, which is what triggers a re-bake. */
    public static int themeStamp() {
        return SBSTheme.TEXT * 31 + SBSTheme.ACCENT;
    }
}
