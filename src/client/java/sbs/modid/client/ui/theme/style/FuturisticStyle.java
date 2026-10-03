/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.theme.style;

import sbs.modid.client.ui.theme.SBSTheme;

/**
 * Sleek and high-tech: the window itself is square, everything <i>inside</i> it is a generously
 * rounded card, surfaces are flat, and cards separate from the panel by brightness rather than by an
 * outline.
 *
 * <p>Previously called "Just Idea". Like {@link ClassicStyle} it carries no palette - it is a shape
 * and surface grammar, so it stays correct under any accent the player picks.
 */
public final class FuturisticStyle implements StyleDefinition {

    @Override
    public String displayName() {
        return "Futuristic";
    }

    @Override
    public String tagline() {
        return "Square windows, generously rounded cards, flat and borderless.";
    }

    @Override
    public void applySurfaces() {
        // Square window, round contents - the shape rule of the whole style.
        SBSTheme.PANEL_CORNER = 0;
        SBSTheme.CORNER_RADIUS = 9;

        // Flat surfaces: this style has no gradients. The panel takes the darker of the two tones so
        // cards above it have somewhere to be lighter.
        SBSTheme.PANEL_FILL_TOP = SBSTheme.PANEL_FILL_BOTTOM;
        SBSTheme.PANEL_BASE = SBSTheme.PANEL_FILL_BOTTOM;

        // Cards separate by BRIGHTNESS, not by an outline: lift them off the panel and reduce the
        // border to a hairline. A visible frame around every card is the single most off-style thing
        // the classic style does.
        SBSTheme.CARD_BG = SBSTheme.lift(SBSTheme.PANEL_FILL_BOTTOM, 0.34F, 0xE8);
        SBSTheme.CARD_BG_HOVER = SBSTheme.lift(SBSTheme.PANEL_FILL_BOTTOM, 0.62F, 0xFF);
        SBSTheme.CARD_BG_DISABLED = SBSTheme.lift(SBSTheme.PANEL_FILL_BOTTOM, 0.14F, 0xC0);
        SBSTheme.SEARCH_FILL = SBSTheme.lift(SBSTheme.PANEL_FILL_BOTTOM, 0.22F, 0xF0);
        SBSTheme.SLOT_BG = SBSTheme.lift(SBSTheme.PANEL_FILL_BOTTOM, 0.26F, 0xFF);
        SBSTheme.CARD_BORDER = SurfaceMaterial.alpha(SBSTheme.CARD_BORDER, 0x22);
        SBSTheme.PANEL_BORDER = SurfaceMaterial.alpha(SBSTheme.PANEL_BORDER, 0x40);
        SBSTheme.PANEL_GLOW = 0x00000000;   // no outer glow; the window edge is a hard line

        // HUD surfaces need their own treatment, and this is why the style used to look like it did
        // not reach them: almost everything above is a PANEL relationship - flatten this gradient,
        // lift that card off the panel behind it - and a HUD card has no panel behind it. Over the
        // world the only thing that reads is the card's own shape and edge, so the style has to say
        // something about those directly: generously rounded, flat, and no outline at all.
        SBSTheme.HUD_CORNER = 9;
        SBSTheme.HUD_CARD_BG = SurfaceMaterial.alpha(SBSTheme.PANEL_FILL_BOTTOM, 0xE6);
        SBSTheme.HUD_CARD_BORDER = 0x00000000;

        // Slots stay square-with-soft-corners. "Round the contents" is a rule about cards and rows,
        // not about a grid of 18 px cells - at this size the card radius turns every item into a
        // circle and the inventory stops reading as a grid at all.
        SBSTheme.SLOT_CORNER = 6;
    }
}
