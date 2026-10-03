/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.fishing.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import sbs.modid.client.ui.hud.edit.logic.HudOpacity;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

/**
 * The fishing HUD's plates. The four fishing panels ({@link FishingHud}, its container variant,
 * {@link BaitHud} and {@link SeaCreatureListHud}) are one look drawn in three files, and this is
 * where the "HUD Opacity" setting becomes that look – so they cannot drift apart, and 0% means the
 * same thing on all of them.
 *
 * <p><b>Why this exists at all:</b> each panel used to build its own colours, and the bottom of the
 * slider did not reach any of them. The header and body added a fixed 60 / 40 alpha points on top of
 * the setting, so a panel dragged to 0% was still a sixth visible; and the ring came from
 * {@link SBSTheme#CARD_BORDER} at full strength, so what was left was a solid frame around it.
 */
final class FishingHudPanel {

    /** How much more solid a tracker's title bar is than the plate under it, in alpha points. */
    static final int HEADER_BOOST = 60;

    /** The same, for the scrollable body between them. */
    static final int BODY_BOOST = 40;

    private FishingHudPanel() {
    }

    /** The "HUD Opacity" setting as an alpha, 0-255. */
    static int alpha(int hudOpacityPercent) {
        return Math.max(0, Math.min(100, hudOpacityPercent)) * 255 / 100;
    }

    /** Draws a panel at {@code alpha}: its rim, and its plate inside that. */
    static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h, int alpha) {
        panel(g, x, y, w, h, alpha, 0);
    }

    /**
     * Draws a panel at {@code alpha}, {@code boost} alpha points more solid than a plain one – how
     * a tracker's title bar and its body sit in front of the plate they share.
     *
     * <p>Every fishing panel is the same two shapes: a border-coloured plate one pixel out, and the
     * fill inside it. When the fill is gone there is no "inside it" left, so drawing the plate would
     * put a filled rectangle in the border colour where the panel used to be – the opposite of
     * transparent. {@link SciFiRender#ring} draws the frame that shape was only ever standing in for.
     *
     * <p>The frame is marked as frame geometry outright rather than left to
     * {@link SBSTheme#isBorderColor} to recognise, for the reason {@link HudOpacity} gives: the
     * classifier reads a colour, and this is a place that already knows the answer.
     */
    static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h, int alpha, int boost) {
        int fill = fill(alpha, boost);
        boolean bodyShows = (fill >>> 24) != 0 && !HudOpacity.hidesBody();

        HudOpacity.beginOutline();
        if (bodyShows) {
            SciFiRender.roundedRect(g, x - 1, y - 1, w + 2, h + 2,
                    SBSTheme.HUD_CORNER + 1, SBSTheme.CARD_BORDER);
        } else {
            SciFiRender.ring(g, x - 1, y - 1, w + 2, h + 2,
                    SBSTheme.HUD_CORNER + 1, SBSTheme.CARD_BORDER);
        }
        HudOpacity.endOutline();

        if (bodyShows) {
            SciFiRender.roundedRect(g, x, y, w, h, SBSTheme.HUD_CORNER, fill);
        }
    }

    /**
     * A panel's fill: the theme's HUD card colour at {@code alpha}, {@code boost} points more solid
     * for the parts that sit in front of the others.
     *
     * <p>The boost is dropped entirely at 0 rather than scaled away gradually. It exists to separate
     * a header from its body, and at 0 there is no header and no body to separate – keeping any of it
     * would only mean the panel never actually goes away.
     */
    private static int fill(int alpha, int boost) {
        int solid = alpha <= 0 ? 0 : Math.min(255, alpha + boost);
        return (solid << 24) | (SBSTheme.HUD_CARD_BG & 0xFFFFFF);
    }
}
