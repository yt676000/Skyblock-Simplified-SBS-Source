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
 * Styled to be indistinguishable from a stock Minecraft screen: the translucent black screen
 * background, flat grey widgets, hard black outlines, square corners, and the beveled edge that
 * makes a vanilla button look raised and a vanilla slot look sunken.
 *
 * <p><b>The one style that hardcodes its colours.</b> Every other style is written as a transform so
 * it survives a custom accent, but this one's entire purpose is to match a fixed external look - a
 * "vanilla" screen tinted lime by the theme engine would be the one thing it must not be. So
 * {@link #applySurfaces()} writes the real values (#C6C6C6 inventory grey, #8B8B8B slot, #A0A0A0
 * muted text, the 0xC0101010 screen tint) rather than deriving them.
 *
 * <p>The bevel is the tell, and it is not a colour or a radius - it is two light edges and two dark
 * ones on every surface, which is exactly the kind of thing a {@link SurfaceMaterial} exists for.
 * Buttons are raised (light top-left), slots are sunken (dark top-left), because that is how vanilla
 * distinguishes something you press from something you drop an item into.
 */
public final class VanillaStyle implements StyleDefinition {

    /** Vanilla's inventory background grey. */
    private static final int GUI_GREY = 0xFFC6C6C6;
    /** Vanilla's slot grey. */
    private static final int SLOT_GREY = 0xFF8B8B8B;
    /** Vanilla's button face. */
    private static final int BUTTON_GREY = 0xFF6E6E6E;
    /** The tint vanilla lays over the world behind any screen. */
    private static final int SCREEN_TINT = 0xC0101010;

    private final SurfaceMaterial material = new BevelMaterial();

    @Override
    public String displayName() {
        return "Vanilla";
    }

    @Override
    public String tagline() {
        return "Indistinguishable from a stock Minecraft screen: flat grey, hard edges, beveled widgets.";
    }

    @Override
    public StylePalette palette() {
        // Neutral greys, so anything still derived by the theme engine lands in the right family.
        return new StylePalette(0xFFFFFF, 0x101010, 0xFFFFFF);
    }

    @Override
    public void applySurfaces() {
        // Vanilla has no rounded corners anywhere.
        SBSTheme.PANEL_CORNER = 0;
        SBSTheme.CORNER_RADIUS = 0;
        SBSTheme.HUD_CORNER = 0;
        SBSTheme.SLOT_CORNER = 0;

        // The screen background is vanilla's own flat tint, not a gradient.
        SBSTheme.BG_TINT = SCREEN_TINT;
        SBSTheme.PANEL_FILL_TOP = SCREEN_TINT;
        SBSTheme.PANEL_FILL_BOTTOM = SCREEN_TINT;
        SBSTheme.PANEL_BORDER = 0xFF000000;
        SBSTheme.PANEL_GLOW = 0x00000000;   // vanilla glows at nothing
        SBSTheme.PANEL_BASE = GUI_GREY;
        SBSTheme.SLOT_BG = SLOT_GREY;

        // Widgets: flat grey plates with a hard black outline, lighter while hovered.
        SBSTheme.CARD_BG = BUTTON_GREY;
        SBSTheme.CARD_BG_HOVER = 0xFF8B8B8B;
        SBSTheme.CARD_BG_DISABLED = 0xFF4A4A4A;
        SBSTheme.CARD_BORDER = 0xFF000000;
        SBSTheme.SEARCH_FILL = 0xFF000000;   // vanilla text fields are black boxes

        SBSTheme.TEXT = 0xFFFFFFFF;
        SBSTheme.TEXT_MUTED = 0xFFA0A0A0;    // vanilla's grey label colour
        SBSTheme.ACCENT = 0xFFFFFFFF;
        SBSTheme.ACCENT_BRIGHT = 0xFFFFFF55; // the yellow vanilla highlights a selection with
        SBSTheme.ACCENT_SOFT = 0x55FFFFFF;

        SBSTheme.HUD_CARD_BG = SCREEN_TINT;
        SBSTheme.HUD_CARD_BORDER = 0xFF000000;
        SBSTheme.HUD_TRACK = 0xFF000000;
    }

    @Override
    public String tooltipSprite() {
        return "vanilla";
    }

    @Override
    public SurfaceMaterial material() {
        return material;
    }

    /** Vanilla's raised / sunken edge, the detail that actually sells the look. */
    private static final class BevelMaterial implements SurfaceMaterial {

        private static final int LIGHT = 0xFFFFFFFF;
        private static final int DARK = 0xFF373737;

        @Override
        public void paintBody(GuiGraphicsExtractor g, int x, int y, int w, int h,
                              int radius, int color, Weight weight) {
            if (w < 4 || h < 4) {
                return;
            }
            // A slot is sunken and a button is raised - the bevel simply runs the other way. The
            // screen background (a PANEL-weight translucent tint) gets none: vanilla does not bevel
            // the darkened world behind a menu.
            if (weight == Weight.PANEL) {
                return;
            }
            boolean sunken = weight == Weight.SLOT;
            int topLeft = SurfaceMaterial.alpha(sunken ? DARK : LIGHT, 0x66);
            int bottomRight = SurfaceMaterial.alpha(sunken ? LIGHT : DARK, 0x66);
            g.fill(x, y, x + w - 1, y + 1, topLeft);
            g.fill(x, y, x + 1, y + h - 1, topLeft);
            g.fill(x + 1, y + h - 1, x + w, y + h, bottomRight);
            g.fill(x + w - 1, y + 1, x + w, y + h, bottomRight);
        }

        @Override
        public boolean paintGlow(GuiGraphicsExtractor g, int x, int y, int w, int h,
                                 int radius, int color, int layers) {
            return true;   // vanilla has no glow; suppress the default rings entirely
        }
    }
}
