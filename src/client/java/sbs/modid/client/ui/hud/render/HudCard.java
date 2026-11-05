/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.hud.render;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.ui.hud.edit.logic.HudOpacity;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.List;
import java.util.Objects;

/**
 * The frame every SBS HUD card is drawn in - background, glow and border, in one place.
 *
 * <p><b>Why this exists.</b> Fifty-odd cards each drew their own frame, and two different frames had
 * grown up among them: most repeated the config panel's three calls (a glow, a bright border and a
 * vertical gradient), while a handful used a flat fill with a thin border and no glow. Both looked
 * fine alone and obviously wrong side by side - the Events card sitting under the Ping card was two
 * different designs stacked on top of each other. A card cannot be inconsistent with the others if
 * there is only one way to draw one.
 *
 * <p>It is also the only way the frame can be made configurable at all. Fifty copies of three calls
 * is fifty places a new setting would have to be threaded through, which is why the frame had no
 * settings before this and the whole thing was pinned to whatever the panel happened to use.
 *
 * <h2>Where the colours come from</h2>
 *
 * Every colour here resolves through {@link CardChrome}, which is the priority order the settings
 * page states: the theme decides, and an override only applies where the player set one. A card
 * never reads a colour of its own.
 */
public final class HudCard {

    /** How far the glow reaches past the card, in pixels. */
    private static final int GLOW_SPREAD = 2;

    private HudCard() {
    }

    /**
     * One text row of a cached card: a left text and an optional right-aligned one, both built and
     * measured once when the card's model changes, so a frame only draws.
     *
     * @param right {@code null} when the row has no right-hand text
     */
    public record Line(Component left, int leftWidth, int leftColor,
                       Component right, int rightWidth, int rightColor) {

        public static Line of(Font font, String left, int leftColor) {
            return new Line(Component.literal(left), font.width(left), leftColor, null, 0, 0);
        }

        public static Line of(Font font, String left, int leftColor, String right, int rightColor) {
            if (right == null || right.isEmpty()) {
                return new Line(Component.literal(left), font.width(left), leftColor, null, 0, rightColor);
            }
            return new Line(Component.literal(left), font.width(left), leftColor,
                    Component.literal(right), font.width(right), rightColor);
        }

        /** Width of the two texts side by side with {@code gap} between them. */
        public int width(int gap) {
            return leftWidth + gap + rightWidth;
        }
    }

    /**
     * A card's immutable draw model: its rows and measured size, plus the version they were built
     * for. A card rebuilds one only when its version stops matching - a tracker change, a setting,
     * or for time-based figures a one-second clock - and otherwise draws the one it has.
     *
     * <p>Published through a {@code volatile} field, so a snapshot built anywhere reads whole.
     */
    public record Snapshot(List<Line> rows, int width, int height, Object version) {

        public boolean isFor(Object currentVersion) {
            return Objects.equals(version, currentVersion);
        }
    }

    /** Draws one cached row: left text at {@code left}, right text flush against {@code right}. */
    public static void drawLine(GuiGraphicsExtractor g, Font font, Line line, int left, int right, int y) {
        g.text(font, line.left(), left, y, line.leftColor());
        if (line.right() != null) {
            g.text(font, line.right(), right - line.rightWidth(), y, line.rightColor());
        }
    }

    private static SBSConfig.ThemeSettings cfg() {
        return ConfigManager.getInstance().get().theme;
    }

    /**
     * Draws the card frame at {@code (x, y)}.
     *
     * <p>Call inside the element's {@code HudLayout.begin} / {@code end} pair, before its content -
     * this paints the background, not a border on top.
     */
    public static void draw(GuiGraphicsExtractor g, int x, int y, int width, int height) {
        draw(g, x, y, width, height, SBSTheme.HUD_CORNER);
    }

    /** As {@link #draw}, for the few cards that carry a corner radius of their own. */
    public static void draw(GuiGraphicsExtractor g, int x, int y, int width, int height, int corner) {
        if (width <= 0 || height <= 0) {
            return;
        }
        int radius = Math.max(0, corner);
        if (cfg().hudCardGlow) {
            SciFiRender.glow(g, x, y, width, height, radius, CardChrome.glow(), GLOW_SPREAD);
        }
        int fillTop = CardChrome.fillTop();
        // "Background off" has to mean an outline with the world behind it, not a plate in the border
        // colour. A card is drawn as a border plate with its body one pixel in, so the body going away
        // does not uncover a frame - it uncovers the plate. Below, that shape is drawn as the ring it
        // is supposed to look like. See HudOpacity#hidesBody for why this cannot be fixed downstream.
        boolean bodyShows = !HudOpacity.hidesBody() && (fillTop >>> 24) != 0;

        // Marked as frame geometry outright rather than left to SBSTheme.isBorderColor to recognise:
        // a player who pinned their own Card Border colour draws this in a colour that predicate has
        // never seen, and the ring would then fade on the body dial - vanishing exactly where the
        // whole point is that it stays.
        HudOpacity.beginOutline();
        if (bodyShows) {
            SciFiRender.roundedRect(g, x, y, width, height, radius, CardChrome.border());
        } else {
            SciFiRender.ring(g, x, y, width, height, radius, CardChrome.border());
        }
        HudOpacity.endOutline();

        if (bodyShows && width > 2 && height > 2) {
            SciFiRender.roundedRectGradient(g, x + 1, y + 1, width - 2, height - 2,
                    Math.max(0, radius - 1), fillTop, CardChrome.fillBottom());
        }
    }
}
