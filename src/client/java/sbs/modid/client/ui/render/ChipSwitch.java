/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.render;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.ui.theme.SBSTheme;

/**
 * N chips sharing one frame, exactly one lit - the choice control for the hand-drawn floating windows
 * (the flip windows' Server / Local switch, the Recipe Viewer's All / Items / Pets / NPCs).
 *
 * <p><b>Why chips.</b> {@code ui/AGENTS.md}: two to five options are shown all at once, one lit, never
 * behind a click-to-cycle - a cycle button shows the current value and hides the others, and seeing
 * the others is the point.
 *
 * <p>A painter and a hit test, not an {@code AbstractWidget}: these windows are drawn by hand into a
 * container screen's render pass and route their own clicks, so there is no widget list to add to.
 * {@link sbs.modid.client.ui.component.SciFiSegmentedSwitch} is the same control for real screens.
 *
 * <p>Every geometry answer comes from {@link #width} and {@link #HEIGHT}, so the caller lays out
 * around what will actually be drawn - the labels are font-measured, and a hard-coded box is a box that
 * overlaps at some GUI scale. Stateless: labels and the lit index are passed in on every call.
 */
public final class ChipSwitch {

    /** Text inset either side of a label. */
    private static final int PADDING = 5;

    /** The control's height, matching the small rows these windows use. */
    public static final int HEIGHT = 11;

    private ChipSwitch() {
    }

    /** The width the control needs at this font. Ask before laying the row out, never after. */
    public static int width(Font font, String... labels) {
        if (font == null) {
            return 0;
        }
        int total = 2;
        for (String label : labels) {
            total += chipWidth(font, label);
        }
        return total;
    }

    private static int chipWidth(Font font, String label) {
        return font.width(label) + PADDING * 2;
    }

    /**
     * Draws the chips at {@code (x, y)} with {@code selected} lit.
     *
     * @return the width consumed, matching {@link #width}
     */
    public static int draw(GuiGraphicsExtractor g, Font font, int x, int y, int selected,
                           int mouseX, int mouseY, String... labels) {
        if (font == null) {
            return 0;
        }
        int total = width(font, labels);
        SciFiRender.roundedRectWithBorder(g, x, y, total, HEIGHT, SBSTheme.CORNER_RADIUS,
                SBSTheme.SEARCH_FILL, SBSTheme.CARD_BORDER);
        int cx = x + 1;
        for (int i = 0; i < labels.length; i++) {
            int w = chipWidth(font, labels[i]);
            drawChip(g, font, cx, y + 1, w, labels[i], i == selected, hovers(mouseX, mouseY, cx, y + 1, w));
            cx += w;
        }
        return total;
    }

    private static void drawChip(GuiGraphicsExtractor g, Font font, int x, int y, int w,
                                 String label, boolean active, boolean hovered) {
        if (active) {
            // The lit chip is solid rather than merely brighter text: at this size a colour
            // difference alone is not readable against the panel gradient underneath.
            SciFiRender.roundedRect(g, x, y, w, HEIGHT - 2, SBSTheme.CORNER_RADIUS, SBSTheme.ACCENT);
        } else if (hovered) {
            SciFiRender.roundedRect(g, x, y, w, HEIGHT - 2, SBSTheme.CORNER_RADIUS,
                    SBSTheme.CARD_BG_HOVER);
        }
        int textY = y + (HEIGHT - 2 - font.lineHeight) / 2 + 1;
        g.text(font, Component.literal(label), x + (w - font.width(label)) / 2, textY,
                active ? SBSTheme.TEXT : hovered ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT_MUTED);
    }

    /**
     * Which chip a click landed on.
     *
     * @return the chip's index, or {@code -1} when the click missed the control - "missed" must not be
     *         confused with "chose the first one"
     */
    public static int hit(Font font, int x, int y, double mouseX, double mouseY, String... labels) {
        if (font == null || mouseY < y || mouseY >= y + HEIGHT) {
            return -1;
        }
        int cx = x + 1;
        for (int i = 0; i < labels.length; i++) {
            int w = chipWidth(font, labels[i]);
            if (mouseX >= cx && mouseX < cx + w) {
                return i;
            }
            cx += w;
        }
        return -1;
    }

    private static boolean hovers(int mouseX, int mouseY, int x, int y, int w) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + HEIGHT - 2;
    }
}
