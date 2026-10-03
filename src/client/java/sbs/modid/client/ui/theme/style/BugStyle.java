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
 * The old-school config look: flat near-black boxes, hairline grey outlines, hard square corners,
 * magenta headings, and no shine of any kind.
 *
 * <p>Its grammar is one rule applied everywhere - <b>everything is an outlined box</b>. A window is a
 * black rectangle with a light grey line around it; the list inside it is another one; every row,
 * every button and every section header is another one again. Depth comes from nesting boxes rather
 * than from gradients, glow or rounded corners, all three of which this style removes outright.
 *
 * <p>Colour is split deliberately. The <b>greys are the identity</b> and are written literally, the
 * way {@link VanillaStyle} writes vanilla's: an outline that drifted lilac under a custom accent
 * would stop being this look. The <b>accent is not</b> - the magenta comes from {@link #palette()},
 * which is only a default, so a player who picks their own colour keeps it and gets their headings
 * in it over the same black-and-grey chrome.
 *
 * <p>The material exists for one job, and it is the job this look creates for itself: with the
 * gradients and the glow gone, an <i>unbordered</i> fill has nothing left to separate it from the
 * black panel behind it. Roughly half of the mod's surfaces are drawn that way. {@code KeylineMaterial}
 * gives them back an edge at the choke point, so they are boxes too without 190-odd call sites having
 * to be told about it.
 */
public final class BugStyle implements StyleDefinition {

    /** The outline around a window. The single brightest thing in the style. */
    private static final int RIM = 0xFFB4B4B4;
    /** The outline around everything smaller: a step down so nesting reads. */
    private static final int RIM_SOFT = 0xFF8C8C8C;
    /** Secondary text. Neutral on purpose - grey labels are part of the look. */
    private static final int LABEL = 0xFF9C9C9C;

    private final SurfaceMaterial material = new KeylineMaterial();

    @Override
    public String displayName() {
        return "Bug";
    }

    @Override
    public String tagline() {
        return "Flat black boxes, hairline grey outlines, square corners, magenta headings.";
    }

    @Override
    public StylePalette palette() {
        // Magenta headings on near-black, with light grey body text.
        return new StylePalette(0xE568E5, 0x101010, 0xE4E4E4);
    }

    @Override
    public void applySurfaces() {
        // Nothing in this style is round. Not the window, not a card, not a slot.
        SBSTheme.PANEL_CORNER = 0;
        SBSTheme.CORNER_RADIUS = 0;
        SBSTheme.HUD_CORNER = 0;
        SBSTheme.SLOT_CORNER = 0;

        // Flat black, no gradient: top and bottom are the same tone. Pulled down from the themed
        // background rather than hardcoded, so a custom background still tints the black - but far
        // enough down that the grey outlines stay the loudest thing on screen.
        int panel = ink(SBSTheme.PANEL_FILL_BOTTOM, 0.055F, 0xF2);
        SBSTheme.PANEL_FILL_TOP = panel;
        SBSTheme.PANEL_FILL_BOTTOM = panel;
        SBSTheme.PANEL_BASE = ink(SBSTheme.PANEL_BASE, 0.07F, 0xFF);
        SBSTheme.BG_TINT = ink(SBSTheme.BG_TINT, 0F, 0x77);

        // Boxes inside the window: a shade lighter than the panel, so nesting is visible before the
        // outline even gets drawn.
        SBSTheme.CARD_BG = ink(SBSTheme.CARD_BG, 0.105F, 0xF5);
        SBSTheme.CARD_BG_HOVER = ink(SBSTheme.CARD_BG_HOVER, 0.19F, 0xFF);
        SBSTheme.CARD_BG_DISABLED = ink(SBSTheme.CARD_BG_DISABLED, 0.07F, 0xC8);
        SBSTheme.SEARCH_FILL = ink(SBSTheme.SEARCH_FILL, 0.02F, 0xFF);
        SBSTheme.SLOT_BG = ink(SBSTheme.SLOT_BG, 0.085F, 0xFF);

        // The outlines, and the absence of any glow behind them.
        SBSTheme.PANEL_BORDER = RIM;
        SBSTheme.CARD_BORDER = RIM_SOFT;
        SBSTheme.PANEL_GLOW = 0x00000000;

        // Grey secondary text: neutral even under a custom accent, which is where TEXT_MUTED would
        // otherwise be derived from.
        SBSTheme.TEXT_MUTED = LABEL;

        // Over the world the same box holds: black plate, grey line, nothing softening either edge.
        SBSTheme.HUD_CARD_BG = ink(SBSTheme.HUD_CARD_BG, 0.055F, 0xD8);
        SBSTheme.HUD_CARD_BORDER = RIM_SOFT;
        SBSTheme.HUD_TRACK = ink(SBSTheme.HUD_TRACK, 0.02F, 0xCC);
    }

    @Override
    public String tooltipSprite() {
        return "bug";
    }

    @Override
    public SurfaceMaterial material() {
        return material;
    }

    /**
     * Pins a colour to near-black at the given brightness, keeping a trace of its hue so a custom
     * background still reads as one. Saturation is capped rather than dropped - at these
     * brightnesses full saturation would show up as a colour cast on what has to look like black.
     */
    private static int ink(int color, float brightness, int alphaValue) {
        float[] hsb = SBSTheme.rgbToHsb(color);
        return (alphaValue << 24) | SBSTheme.hsbToRgb(hsb[0], Math.min(hsb[1], 0.30F), brightness);
    }

    /** Gives an unbordered surface the outline this style says every surface has. */
    private static final class KeylineMaterial implements SurfaceMaterial {

        /**
         * Faint on purpose. A surface drawn <i>with</i> a border gets this line one pixel inside its
         * rim, and at this alpha that reads as the rim being very slightly softer rather than as a
         * second frame around everything.
         */
        private static final int KEYLINE = SurfaceMaterial.alpha(RIM_SOFT, 0x34);

        /**
         * Smallest surface worth outlining. Well above a progress bar's fill or a divider strip -
         * those are shapes, not boxes, and an outline around them would read as a mistake.
         */
        private static final int MIN_W = 30;
        private static final int MIN_H = 12;

        @Override
        public void paintBody(GuiGraphicsExtractor g, int x, int y, int w, int h,
                              int radius, int color, Weight weight) {
            if (weight == Weight.SLOT || w < MIN_W || h < MIN_H) {
                return;
            }
            g.fill(x, y, x + w, y + 1, KEYLINE);
            g.fill(x, y + h - 1, x + w, y + h, KEYLINE);
            g.fill(x, y + 1, x + 1, y + h - 1, KEYLINE);
            g.fill(x + w - 1, y + 1, x + w, y + h - 1, KEYLINE);
        }

        @Override
        public boolean paintGlow(GuiGraphicsExtractor g, int x, int y, int w, int h,
                                 int radius, int color, int layers) {
            return true;   // an outline is a hard line here; nothing bleeds past it
        }
    }
}
