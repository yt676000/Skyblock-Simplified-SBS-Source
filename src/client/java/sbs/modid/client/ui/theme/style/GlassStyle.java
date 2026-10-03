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
 * A pane of glass: the world stays visible through every surface, corners are all but square, and
 * the only thing that draws a shape is a single hairline edge.
 *
 * <p>Its grammar is the inverse of every other style here - the others say what a surface is
 * <i>made</i> of, this one says how little of it there is. Fills drop to a tint, the gradient and
 * the outer glow go entirely, and depth comes from the one thing left: a brighter line along the
 * top of each surface, the way light catches the edge of a sheet of glass.
 *
 * <p><b>No palette</b>, like {@link ClassicStyle} and {@link FuturisticStyle}: glass has no colour
 * of its own, it only tints whatever is behind it. So the player's three base colours drive it
 * unchanged and it stays correct under any accent - which is also what makes it the style that
 * composes best with the Theme page's Surface Opacity slider.
 *
 * <p>The material is not decoration here, it is what keeps the style usable. At these alphas an
 * <i>unbordered</i> fill is invisible against the world behind it, and roughly half of the mod's
 * surfaces are drawn that way - {@link GlassMaterial} gives them back an edge at the choke point,
 * so a row or a button is still a shape without 190-odd call sites having to be told about it.
 */
public final class GlassStyle implements StyleDefinition {

    /** Window and HUD panes: enough tint to read text over grass, far short of hiding the world. */
    private static final int PANE = 0x4A;

    /** Cards, rows and buttons inside a pane. A step up from it, so nesting still reads. */
    private static final int CELL = 0x33;
    private static final int CELL_HOVER = 0x5E;
    private static final int CELL_DISABLED = 0x1C;

    /** Item cells. The faintest surface in the style - a grid of items must read as the items. */
    private static final int SLOT = 0x24;

    private final SurfaceMaterial material = new GlassMaterial();

    @Override
    public String displayName() {
        return "Glass";
    }

    @Override
    public String tagline() {
        return "See-through panes, one hairline edge, no fills and no glow.";
    }

    @Override
    public void applySurfaces() {
        // Nothing here is more than a hair round. A pane has an edge, not a corner.
        SBSTheme.PANEL_CORNER = 2;
        SBSTheme.CORNER_RADIUS = 2;
        SBSTheme.HUD_CORNER = 2;
        SBSTheme.SLOT_CORNER = 2;

        // One flat tint, top and bottom alike: a gradient is a lit solid, which is the one thing
        // glass is not. Derived from the themed background so a custom colour still tints the pane.
        int pane = SBSTheme.PANEL_FILL_BOTTOM;
        SBSTheme.PANEL_FILL_TOP = SurfaceMaterial.alpha(pane, PANE);
        SBSTheme.PANEL_FILL_BOTTOM = SurfaceMaterial.alpha(pane, PANE);
        SBSTheme.PANEL_BASE = SurfaceMaterial.alpha(pane, PANE);
        SBSTheme.BG_TINT = SurfaceMaterial.alpha(SBSTheme.BG_TINT, 0x1E);

        SBSTheme.CARD_BG = SBSTheme.lift(pane, 0.18F, CELL);
        SBSTheme.CARD_BG_HOVER = SBSTheme.lift(pane, 0.46F, CELL_HOVER);
        SBSTheme.CARD_BG_DISABLED = SBSTheme.lift(pane, 0.06F, CELL_DISABLED);
        SBSTheme.SEARCH_FILL = SBSTheme.lift(pane, 0.10F, CELL);
        SBSTheme.SLOT_BG = SBSTheme.lift(pane, 0.12F, SLOT);

        // The edge is the whole style, so it is the one thing allowed to be bright. Pale rather than
        // white: lifting the accent keeps a custom colour visible in it instead of bleaching it out.
        int edge = SBSTheme.lift(SBSTheme.ACCENT, 0.72F, 0xFF);
        SBSTheme.PANEL_BORDER = SurfaceMaterial.alpha(edge, 0xA6);
        SBSTheme.CARD_BORDER = SurfaceMaterial.alpha(edge, 0x40);
        SBSTheme.PANEL_GLOW = 0x00000000;   // a pane's edge is a hard line; nothing bleeds past it

        // Secondary text sits over the world here, not over a solid panel, so it is lifted towards
        // the primary text colour - the muted grey that reads fine on navy disappears on grass.
        SBSTheme.TEXT_MUTED = SBSTheme.lift(SBSTheme.TEXT_MUTED, 0.30F, 0xFF);

        // Over the world the same pane holds, a touch more tinted because there is no window behind
        // it to darken what shows through.
        SBSTheme.HUD_CARD_BG = SurfaceMaterial.alpha(pane, 0x52);
        SBSTheme.HUD_CARD_BORDER = SurfaceMaterial.alpha(edge, 0x59);
        SBSTheme.HUD_TRACK = SurfaceMaterial.alpha(SBSTheme.HUD_TRACK, 0x3C);
    }

    @Override
    public String tooltipSprite() {
        return "glass";
    }

    @Override
    public SurfaceMaterial material() {
        return material;
    }

    /**
     * Gives a see-through surface the edge it needs to be a shape at all, plus the highlight along
     * its top that is the only depth cue this style keeps.
     *
     * <p>Both colours are derived from the fill that was just painted rather than written down, so
     * they follow a custom theme colour instead of pinning the style to one - and so the keyline on
     * a card is a shade of that card, never a second frame in some unrelated grey.
     */
    private static final class GlassMaterial implements SurfaceMaterial {

        /** Smallest surface worth an edge. Below this it is a bar or a divider, not a pane. */
        private static final int MIN_W = 24;
        private static final int MIN_H = 10;

        @Override
        public void paintBody(GuiGraphicsExtractor g, int x, int y, int w, int h,
                              int radius, int color, Weight weight) {
            if (weight == Weight.SLOT || w < MIN_W || h < MIN_H) {
                return;
            }
            int keyline = SurfaceMaterial.alpha(SurfaceMaterial.shade(color, 0.70F), 0x2E);
            g.fill(x + radius, y, x + w - radius, y + 1, keyline);
            g.fill(x + radius, y + h - 1, x + w - radius, y + h, keyline);
            g.fill(x, y + radius, x + 1, y + h - radius, keyline);
            g.fill(x + w - 1, y + radius, x + w, y + h - radius, keyline);

            // The light on the edge: one brighter line just inside the top. This is what separates a
            // pane from a flat wash of colour, and it is the whole reason the style needs a material.
            int sheen = SurfaceMaterial.alpha(SurfaceMaterial.shade(color, 0.95F), 0x1C);
            g.fill(x + radius + 1, y + 1, x + w - radius - 1, y + 2, sheen);
        }

        @Override
        public boolean paintGlow(GuiGraphicsExtractor g, int x, int y, int w, int h,
                                 int radius, int color, int layers) {
            return true;   // no bloom: the edge is the shape, and a glow would soften it back away
        }
    }
}
