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
 * Arcane: deep violet surfaces, gold hairlines, rune ticks cut into every frame, and a soft inner
 * bloom instead of a hard edge.
 *
 * <p>The bloom is deliberately drawn <i>inward</i>. An outer glow makes a window look like it is
 * lit from outside; an inner one makes it look like the surface itself is holding light, which is
 * the difference between "neon" and "enchanted". The rune ticks are placed by the shared coordinate
 * hash, so they sit at irregular intervals like carved marks rather than at a machine's spacing -
 * and, being derived from position, they stay put while a window is dragged.
 */
public final class MagicalStyle implements StyleDefinition {

    private static final int GOLD = 0xFFE8C56A;
    private static final int ARCANE = 0xFFA96BFF;

    private final SurfaceMaterial material = new ArcaneMaterial();

    @Override
    public String displayName() {
        return "Magical";
    }

    @Override
    public String tagline() {
        return "Deep violet and gold, carved rune ticks, a soft arcane bloom inside every frame.";
    }

    @Override
    public StylePalette palette() {
        // Gold accent, deep violet background, moonlit text.
        return new StylePalette(0xE8C56A, 0x1A0F2E, 0xEDE3FF);
    }

    @Override
    public void applySurfaces() {
        // Softly rounded throughout - nothing arcane has machined edges.
        SBSTheme.PANEL_CORNER = 6;
        SBSTheme.CORNER_RADIUS = 5;
        SBSTheme.HUD_CORNER = 6;
        SBSTheme.SLOT_CORNER = 3;

        SBSTheme.PANEL_FILL_TOP = 0xF2241542;
        SBSTheme.PANEL_FILL_BOTTOM = 0xF2120A22;
        SBSTheme.PANEL_BASE = 0xFF1B1033;
        SBSTheme.BG_TINT = 0x660A0518;

        // Gold hairline, violet halo.
        SBSTheme.PANEL_BORDER = SurfaceMaterial.alpha(GOLD, 0xAA);
        SBSTheme.PANEL_GLOW = 0x3AA96BFF;
        SBSTheme.CARD_BORDER = SurfaceMaterial.alpha(ARCANE, 0x66);

        SBSTheme.CARD_BG = 0xF02E1B52;
        SBSTheme.CARD_BG_HOVER = 0xFF432876;
        SBSTheme.CARD_BG_DISABLED = 0xC01D1236;
        SBSTheme.SEARCH_FILL = 0xF0130B26;
        SBSTheme.SLOT_BG = 0xFF251647;

        SBSTheme.HUD_CARD_BG = 0xE01A0F30;
        SBSTheme.HUD_CARD_BORDER = SurfaceMaterial.alpha(GOLD, 0x88);
        SBSTheme.HUD_TRACK = 0xC0140C26;
    }

    @Override
    public String tooltipSprite() {
        return "magical";
    }

    @Override
    public SurfaceMaterial material() {
        return material;
    }

    /** Inner bloom, carved rune ticks, and a violet halo in place of the sci-fi glow. */
    private static final class ArcaneMaterial implements SurfaceMaterial {

        @Override
        public void paintBody(GuiGraphicsExtractor g, int x, int y, int w, int h,
                              int radius, int color, Weight weight) {
            if (weight == Weight.SLOT || w < 14 || h < 12) {
                return;
            }
            // The bloom: two inset rings, faint and violet, so the light looks held by the surface.
            int inner = SurfaceMaterial.alpha(SurfaceMaterial.mix(color, ARCANE, 0.55F), 0x22);
            int i = Math.max(2, radius);
            g.fill(x + i, y + i, x + w - i, y + i + 1, inner);
            g.fill(x + i, y + h - i - 1, x + w - i, y + h - i, inner);
            if (weight == Weight.PANEL) {
                int deep = SurfaceMaterial.alpha(SurfaceMaterial.mix(color, ARCANE, 0.35F), 0x18);
                g.fill(x + i + 2, y + i + 2, x + w - i - 2, y + i + 3, deep);
                g.fill(x + i + 2, y + h - i - 3, x + w - i - 2, y + h - i - 2, deep);
            }
        }

        @Override
        public void paintFrame(GuiGraphicsExtractor g, int x, int y, int w, int h,
                               int radius, int border, Weight weight) {
            if (weight != Weight.PANEL || w < 60 || h < 40) {
                return;
            }
            // Rune ticks along the top and bottom rails: short gold marks at irregular spacing.
            int tick = SurfaceMaterial.alpha(GOLD, 0xCC);
            int margin = Math.max(8, radius + 6);
            for (int px = x + margin; px < x + w - margin; px += 9) {
                int n = SurfaceMaterial.noise(px, y);
                if (n % 3 == 0) {
                    continue;   // gaps, so the marks read as carved rather than printed
                }
                int len = 2 + (n >> 4) % 3;
                g.fill(px, y + 1, px + 1, y + 1 + len, tick);
                g.fill(px, y + h - 1 - len, px + 1, y + h - 1, tick);
            }
        }

        @Override
        public boolean paintGlow(GuiGraphicsExtractor g, int x, int y, int w, int h,
                                 int radius, int color, int layers) {
            // A wider, softer violet halo than the default rings - the arcane version of a glow.
            sbs.modid.client.ui.hud.edit.logic.HudOpacity.beginOutline();
            for (int i = layers + 2; i >= 1; i--) {
                int fade = SurfaceMaterial.alpha(ARCANE, Math.max(4, 0x26 - i * 4));
                sbs.modid.client.ui.render.SciFiRender.roundedRectRaw(
                        g, x - i, y - i, w + 2 * i, h + 2 * i, radius + i, fade);
            }
            sbs.modid.client.ui.hud.edit.logic.HudOpacity.endOutline();
            return true;
        }
    }
}
