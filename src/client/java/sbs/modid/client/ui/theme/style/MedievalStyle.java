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
 * Rustic and hand-made: weathered oak panels with visible grain and plank seams, warm parchment
 * cards, and blackened iron edges with corner brackets.
 *
 * <p>The material does the work here. Wood is not a colour, it is a colour <i>plus</i> grain running
 * one way and seams breaking it up; parchment is a warm plate with an uneven edge. Both are drawn as
 * a handful of horizontal strips over whatever fill the theme engine produced, so a player who picks
 * a green accent gets green-stained oak rather than a broken style.
 */
public final class MedievalStyle implements StyleDefinition {

    /** Blackened iron, for edges and brackets. */
    private static final int IRON = 0xFF6E6A63;
    private static final int IRON_LIGHT = 0xFFA8A296;

    private final SurfaceMaterial material = new TimberMaterial();

    @Override
    public String displayName() {
        return "Medieval";
    }

    @Override
    public String tagline() {
        return "Weathered oak with visible grain, warm parchment cards, blackened iron brackets.";
    }

    @Override
    public StylePalette palette() {
        // Iron accent, dark oak background, warm parchment text.
        return new StylePalette(0xA8A296, 0x2A1F16, 0xEFE2C6);
    }

    @Override
    public void applySurfaces() {
        // Timber is cut square and pegged; only the parchment inlays get a soft corner.
        SBSTheme.PANEL_CORNER = 2;
        SBSTheme.CORNER_RADIUS = 3;
        SBSTheme.HUD_CORNER = 2;
        SBSTheme.SLOT_CORNER = 1;

        // Oak boards: a shallow gradient so the panel reads as a lit surface rather than a flat fill.
        SBSTheme.PANEL_FILL_TOP = 0xF23B2C1F;
        SBSTheme.PANEL_FILL_BOTTOM = 0xF2241A12;
        SBSTheme.PANEL_BASE = 0xFF2E2118;
        SBSTheme.BG_TINT = 0x66120C08;

        // Iron banding around the window, and no sci-fi glow - iron does not shine in the dark.
        SBSTheme.PANEL_BORDER = IRON_LIGHT;
        SBSTheme.PANEL_GLOW = 0x1A000000;
        SBSTheme.CARD_BORDER = SurfaceMaterial.alpha(IRON, 0x88);

        // Parchment cards: warm, light and clearly a different material from the boards behind them.
        SBSTheme.CARD_BG = 0xF04A382A;
        SBSTheme.CARD_BG_HOVER = 0xFF634B37;
        SBSTheme.CARD_BG_DISABLED = 0xC0332619;
        SBSTheme.SEARCH_FILL = 0xF01E1610;
        SBSTheme.SLOT_BG = 0xFF3A2B1F;

        SBSTheme.HUD_CARD_BG = 0xE0261C13;
        SBSTheme.HUD_CARD_BORDER = SurfaceMaterial.alpha(IRON_LIGHT, 0x99);
        SBSTheme.HUD_TRACK = 0xC01A130D;
    }

    @Override
    public String tooltipSprite() {
        return "medieval";
    }

    @Override
    public SurfaceMaterial material() {
        return material;
    }

    /** Wood grain, plank seams and iron corner brackets. */
    private static final class TimberMaterial implements SurfaceMaterial {

        /** Rows between grain lines. Sparse on purpose - dense grain reads as noise, not oak. */
        private static final int GRAIN_STEP = 5;
        /** A panel taller than this gets plank seams breaking it into boards. */
        private static final int PLANK_HEIGHT = 46;

        @Override
        public void paintBody(GuiGraphicsExtractor g, int x, int y, int w, int h,
                              int radius, int color, Weight weight) {
            if (weight == Weight.SLOT || w < 12 || h < 8) {
                return;   // an item cell is not a plank
            }
            int grain = SurfaceMaterial.alpha(SurfaceMaterial.shade(color, -0.34F), 0x5E);
            int inset = Math.max(1, radius);

            // Grain: short horizontal strokes at a stable pseudo-random inset, so the surface has a
            // direction without turning into stripes.
            for (int row = y + inset + 2; row < y + h - inset - 1; row += GRAIN_STEP) {
                int n = SurfaceMaterial.noise(x, row);
                int left = x + inset + (n % Math.max(1, w / 4));
                int right = x + w - inset - ((n >> 8) % Math.max(1, w / 5));
                if (right - left > 6) {
                    g.fill(left, row, right, row + 1, grain);
                }
            }

            // Plank seams: a dark line with a lighter one under it, the shadow-and-highlight of a
            // board edge. Only on surfaces big enough to be made of more than one board.
            if (weight == Weight.PANEL && h > PLANK_HEIGHT) {
                int seam = SurfaceMaterial.alpha(SurfaceMaterial.shade(color, -0.62F), 0xAA);
                int lip = SurfaceMaterial.alpha(SurfaceMaterial.shade(color, 0.26F), 0x66);
                for (int row = y + PLANK_HEIGHT; row < y + h - 6; row += PLANK_HEIGHT) {
                    g.fill(x + inset, row, x + w - inset, row + 1, seam);
                    g.fill(x + inset, row + 1, x + w - inset, row + 2, lip);
                }
            }
        }

        @Override
        public void paintFrame(GuiGraphicsExtractor g, int x, int y, int w, int h,
                               int radius, int border, Weight weight) {
            if (weight != Weight.PANEL || w < 40 || h < 40) {
                return;
            }
            // Iron corner brackets: the L-shaped straps a real chest has at its corners.
            int arm = Math.min(14, Math.min(w, h) / 5);
            int strap = SurfaceMaterial.alpha(IRON_LIGHT, 0xCC);
            corner(g, x, y, arm, strap, true, true);
            corner(g, x + w, y, arm, strap, false, true);
            corner(g, x, y + h, arm, strap, true, false);
            corner(g, x + w, y + h, arm, strap, false, false);
        }

        /** One L-shaped strap, drawn inward from the given corner. */
        private static void corner(GuiGraphicsExtractor g, int cx, int cy, int arm, int color,
                                   boolean left, boolean top) {
            int x0 = left ? cx : cx - arm;
            int x1 = left ? cx + arm : cx;
            int y0 = top ? cy : cy - arm;
            int y1 = top ? cy + arm : cy;
            g.fill(x0, top ? cy : cy - 2, x1, top ? cy + 2 : cy, color);
            g.fill(left ? cx : cx - 2, y0, left ? cx + 2 : cx, y1, color);
        }
    }
}
