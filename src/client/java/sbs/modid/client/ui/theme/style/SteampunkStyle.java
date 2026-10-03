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
 * Brass and copper machinery: warm metal plates with a polished sheen across the top, a doubled
 * copper frame, and rivets punched through at every corner.
 *
 * <p>The sheen is what makes it read as metal rather than as brown. A real brass plate is brightest
 * where the light hits its upper third and falls off below, so the material lays a short bright band
 * near the top edge and a darker one along the bottom - two fills that turn a flat rectangle into a
 * sheet of metal.
 */
public final class SteampunkStyle implements StyleDefinition {

    private static final int BRASS = 0xFFD6A94E;
    private static final int COPPER = 0xFFB5651D;

    private final SurfaceMaterial material = new BrassMaterial();

    @Override
    public String displayName() {
        return "Steampunk";
    }

    @Override
    public String tagline() {
        return "Brass plates with a polished sheen, doubled copper framing, riveted corners.";
    }

    @Override
    public StylePalette palette() {
        // Brass accent, sooted walnut background, aged paper text.
        return new StylePalette(0xD6A94E, 0x2B1D12, 0xF2E3C4);
    }

    @Override
    public void applySurfaces() {
        // Machined plate: barely eased corners, because metal is cut, not moulded.
        SBSTheme.PANEL_CORNER = 3;
        SBSTheme.CORNER_RADIUS = 2;
        SBSTheme.HUD_CORNER = 3;
        SBSTheme.SLOT_CORNER = 1;

        SBSTheme.PANEL_FILL_TOP = 0xF23A2A1B;
        SBSTheme.PANEL_FILL_BOTTOM = 0xF220150D;
        SBSTheme.PANEL_BASE = 0xFF2E2116;
        SBSTheme.BG_TINT = 0x66140D07;

        // Copper framing with a warm halo - the one style where the glow belongs, as lamp light on
        // metal rather than as a neon rim.
        SBSTheme.PANEL_BORDER = COPPER;
        SBSTheme.PANEL_GLOW = 0x33B5651D;
        SBSTheme.CARD_BORDER = SurfaceMaterial.alpha(BRASS, 0x77);

        SBSTheme.CARD_BG = 0xF04A3520;
        SBSTheme.CARD_BG_HOVER = 0xFF67492C;
        SBSTheme.CARD_BG_DISABLED = 0xC0322414;
        SBSTheme.SEARCH_FILL = 0xF01C1309;
        SBSTheme.SLOT_BG = 0xFF3B2A1A;

        SBSTheme.HUD_CARD_BG = 0xE0281B10;
        SBSTheme.HUD_CARD_BORDER = SurfaceMaterial.alpha(BRASS, 0x99);
        SBSTheme.HUD_TRACK = 0xC01B1209;
    }

    @Override
    public String tooltipSprite() {
        return "steampunk";
    }

    @Override
    public SurfaceMaterial material() {
        return material;
    }

    /** Polished sheen, doubled frame and corner rivets. */
    private static final class BrassMaterial implements SurfaceMaterial {

        @Override
        public void paintBody(GuiGraphicsExtractor g, int x, int y, int w, int h,
                              int radius, int color, Weight weight) {
            if (weight == Weight.SLOT || w < 14 || h < 10) {
                return;
            }
            int inset = Math.max(1, radius);
            // The polished band: brightest just below the top edge, where light lands on a plate.
            int sheen = SurfaceMaterial.alpha(SurfaceMaterial.mix(color, BRASS, 0.5F), 0x2E);
            int band = Math.max(1, h / 6);
            g.fill(x + inset, y + inset, x + w - inset, y + inset + band, sheen);
            // ...and the fall-off along the bottom, which is what stops it looking like a highlight
            // pasted on a flat colour.
            int shadow = SurfaceMaterial.alpha(SurfaceMaterial.shade(color, -0.4F), 0x33);
            g.fill(x + inset, y + h - inset - band, x + w - inset, y + h - inset, shadow);
        }

        @Override
        public void paintFrame(GuiGraphicsExtractor g, int x, int y, int w, int h,
                               int radius, int border, Weight weight) {
            if (weight == Weight.SLOT || w < 20 || h < 16) {
                return;
            }
            // Doubled frame: the copper rim the caller drew, plus a brass line one pixel inside it.
            int inner = SurfaceMaterial.alpha(BRASS, weight == Weight.PANEL ? 0x99 : 0x55);
            int i = 2;
            g.fill(x + i, y + i, x + w - i, y + i + 1, inner);
            g.fill(x + i, y + h - i - 1, x + w - i, y + h - i, inner);
            g.fill(x + i, y + i, x + i + 1, y + h - i, inner);
            g.fill(x + w - i - 1, y + i, x + w - i, y + h - i, inner);

            if (weight != Weight.PANEL || w < 44 || h < 36) {
                return;
            }
            // Rivets: a bright 2x2 head with a dark underside, punched inside each corner.
            int head = SurfaceMaterial.alpha(SurfaceMaterial.shade(BRASS, 0.35F), 0xE0);
            int under = SurfaceMaterial.alpha(SurfaceMaterial.shade(COPPER, -0.45F), 0xB0);
            int pad = 5;
            rivet(g, x + pad, y + pad, head, under);
            rivet(g, x + w - pad - 2, y + pad, head, under);
            rivet(g, x + pad, y + h - pad - 2, head, under);
            rivet(g, x + w - pad - 2, y + h - pad - 2, head, under);
        }

        private static void rivet(GuiGraphicsExtractor g, int rx, int ry, int head, int under) {
            g.fill(rx, ry, rx + 2, ry + 2, head);
            g.fill(rx, ry + 2, rx + 2, ry + 3, under);
        }
    }
}
