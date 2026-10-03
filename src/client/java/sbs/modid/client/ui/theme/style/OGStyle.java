/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.theme.style;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import sbs.modid.client.ui.theme.SBSTheme;

/**
 * The old-school beveled config look: every surface is a raised plate of cold near-black, lit from
 * the top left and shadowed to the bottom right, dropping a soft shadow onto whatever is behind it.
 * Square corners throughout, grey text, a violet accent.
 *
 * <p><b>The bevel is the whole style</b>, and it is not a colour - it is four edges that disagree.
 * One pixel of a lighter tone along the top and left, one pixel of a much darker tone along the
 * right and bottom, and two pixels of translucent black falling away from the bottom-right corner.
 * That is what makes a plate look like it is sitting <i>on</i> the screen rather than painted into
 * it, and it is the reason the look survives having no gradients, no glow and no curves anywhere.
 *
 * <p><b>Three tones, not two.</b> The layering is what sells it: a plate has to be visibly lighter
 * than the surface it sits on, or the bevel has nothing to catch. So the window is a middle tone,
 * cards are a step up from it, and the highlight edge is a step up again - which is why a card reads
 * as an outlined box here without ever drawing an outline.
 *
 * <p>Colour identity is the cold blue-black (its greys are all a touch blue, never neutral) plus the
 * violet accent, supplied through {@link #palette()} so a player who picks their own colour keeps it
 * and gets violet's job done in theirs.
 */
public final class OGStyle implements StyleDefinition {

    /** Window / well: the tone a plate has to sit on to be visible. */
    private static final int WELL = 0xF014141A;
    /** The plate every card, row and button is cut from. */
    private static final int PLATE = 0xFF202026;
    /** The dark seam a plate sits in - what a border is, in this style. */
    private static final int SHADED = 0xFF101016;
    /** A sunken well: text fields and anything the eye should read as cut into the plate. */
    private static final int SUNKEN = 0xFF08080E;

    private final SurfaceMaterial material = new PlateMaterial();

    @Override
    public String displayName() {
        return "OG";
    }

    @Override
    public String tagline() {
        return "Beveled near-black plates, drop shadows, square corners, grey text and violet.";
    }

    @Override
    public StylePalette palette() {
        // Violet accent on cold blue-black, with grey rather than white body text.
        return new StylePalette(0xA368EF, 0x202026, 0xC0C0C0);
    }

    @Override
    public void applySurfaces() {
        // Nothing curves. A plate is cut square.
        SBSTheme.PANEL_CORNER = 0;
        SBSTheme.CORNER_RADIUS = 0;
        SBSTheme.HUD_CORNER = 0;
        SBSTheme.SLOT_CORNER = 0;

        // Flat: top and bottom are the same tone, because a bevel and a gradient are two different
        // ways of saying "this has depth" and this style only uses the first one.
        SBSTheme.PANEL_FILL_TOP = WELL;
        SBSTheme.PANEL_FILL_BOTTOM = WELL;
        SBSTheme.PANEL_BASE = 0xFF16161C;
        SBSTheme.BG_TINT = 0x90101010;

        // The plates, a clear step up from the window so their lit edge has something to be lighter
        // than. Hover lifts the plate rather than tinting it.
        SBSTheme.CARD_BG = PLATE;
        SBSTheme.CARD_BG_HOVER = 0xFF2E2E36;
        SBSTheme.CARD_BG_DISABLED = 0xFF17171C;
        SBSTheme.SEARCH_FILL = SUNKEN;
        SBSTheme.SLOT_BG = SHADED;

        // Borders sit OUTSIDE the bevel (see PlateMaterial), so they are the dark seam a plate has
        // around it rather than a highlight - a light ring there would cancel the bevel out. No glow
        // either: depth is the drop shadow's job and the two would fight.
        SBSTheme.PANEL_BORDER = SHADED;
        SBSTheme.CARD_BORDER = SHADED;
        SBSTheme.PANEL_GLOW = 0x00000000;

        // Grey text, two steps of it, and neither is white.
        SBSTheme.TEXT = 0xFFC0C0C0;
        SBSTheme.TEXT_MUTED = 0xFFA0A0A0;
        // What a selection or a title is picked out in. Points at the live accent rather than a
        // literal, so it is violet by default and follows the player's colour when they set one -
        // white highlights would be invisible against grey body text anyway.
        SBSTheme.ACCENT_BRIGHT = SBSTheme.ACCENT;

        // Over the world, the same plate.
        SBSTheme.HUD_CARD_BG = 0xE8202026;
        SBSTheme.HUD_CARD_BORDER = SHADED;
        SBSTheme.HUD_TRACK = 0xE008080E;
    }

    @Override
    public String tooltipSprite() {
        return "og";
    }

    @Override
    public SurfaceMaterial material() {
        return material;
    }

    /**
     * The raised plate: a lit top-left edge, a shadowed bottom-right edge, and a soft shadow cast
     * down-right onto whatever is behind.
     *
     * <p>Both halves are derived from the surface's own fill rather than written as literals, so the
     * bevel keeps working - and keeps the same light direction - under a custom background colour.
     * The two amounts are what turns this style's plate tone into its own edge tones.
     */
    private static final class PlateMaterial implements SurfaceMaterial {

        /** How far the lit edge is pushed towards white. */
        private static final float LIGHTEN = 0.07F;
        /** How far the shadowed edge is pulled towards black. */
        private static final float DARKEN = -0.5F;
        /** Thickness of the cast shadow, and how far it is offset down and right. */
        private static final int DROP = 2;
        /** Translucent black - a real shadow, so it darkens whatever it lands on. */
        private static final int SHADOW = 0x70000000;

        /**
         * Smallest surface that counts as a plate rather than a shape.
         *
         * <p>A bevel costs one pixel on every side, so it only makes sense on something with a face
         * left over in the middle. An XP bar is 4 px tall and a slider track 7 - bevel those and half
         * the bar <i>is</i> edge, with the last column coming out at half brightness as a dark notch
         * on the end. Found exactly that way on the active-pet chroma bar.
         */
        private static final int MIN_W = 12;
        private static final int MIN_H = 8;

        /**
         * The bevel lives on the <b>body</b> pass, not the frame pass, and that is the one decision
         * in this class worth explaining.
         *
         * <p>Only surfaces drawn <i>with</i> a border get a frame pass, and here that is barely half
         * of them - the rest are plain fills. A bevel hung off the frame would light up half the mod
         * and leave the other half a flat rectangle, which is the exact failure this material system
         * exists to prevent. The body pass runs for every surface there is, so the plates come out
         * consistent; a bordered surface simply ends up with its border ring sitting outside the
         * bevel, where it reads as the dark seam these plates have anyway.
         */
        @Override
        public void paintBody(GuiGraphicsExtractor g, int x, int y, int w, int h,
                              int radius, int color, Weight weight) {
            if (weight == Weight.SLOT || w < MIN_W || h < MIN_H) {
                return;   // an item cell is a hole; a bar or a track is a shape - neither is a plate
            }
            int lit = SurfaceMaterial.shade(color, LIGHTEN);
            int shaded = SurfaceMaterial.shade(color, DARKEN);
            g.fill(x, y, x + 1, y + h, lit);                        // left
            g.fill(x + 1, y, x + w, y + 1, lit);                    // top
            g.fill(x + w - 1, y + 1, x + w, y + h, shaded);         // right
            g.fill(x + 1, y + h - 1, x + w - 1, y + h, shaded);     // bottom

            // Only plates cast a shadow: a window is the thing plates sit on, so it does not drop
            // one onto the world behind it.
            if (weight == Weight.CARD) {
                g.fill(x + w, y + DROP, x + w + DROP, y + h + DROP, SHADOW);
                g.fill(x + DROP, y + h, x + w, y + h + DROP, SHADOW);
            }
        }

        @Override
        public boolean paintGlow(GuiGraphicsExtractor g, int x, int y, int w, int h,
                                 int radius, int color, int layers) {
            return true;   // the drop shadow is this style's only depth cue
        }
    }
}
