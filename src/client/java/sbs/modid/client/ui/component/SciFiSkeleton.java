/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.component;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

/**
 * Placeholder rows drawn where settings rows are about to appear.
 *
 * <p>Not a widget: it holds no state, takes no input and registers nothing. It exists so a screen
 * that defers building its rows has something honest to draw in the meantime — the shape of the
 * content, at the size the content will be, rather than an empty box or a spinner.
 *
 * <p><b>Why the shape matters.</b> The point of a skeleton is that the layout does not move when
 * the real rows land: the card, the label and the value pill are drawn at the same metrics
 * {@code SettingRowList} uses, so the swap reads as text arriving rather than as the panel
 * rebuilding itself. A generic grey block would be a loading indicator with extra steps.
 *
 * <p>Deliberately low contrast. These rows carry no information and must not compete with the
 * chrome that is already real — the sidebar and the search bar are usable while this is on screen.
 */
public final class SciFiSkeleton {

    /** Label-bar widths as a fraction of the room available, cycled so the block does not read as a grid. */
    private static final float[] LABEL_FRACTIONS = {0.55f, 0.40f, 0.62f, 0.34f, 0.48f, 0.58f, 0.44f};

    /** Height of the label bar and the value pill. About the cap height of the UI font. */
    private static final int BAR_HEIGHT = 7;

    /** Matches the value box on a real row, so the right-hand edge does not shift on the swap. */
    private static final int PILL_WIDTH = 30;

    /** Left padding of the label, from {@code ui/AGENTS.md}'s row anatomy. */
    private static final int LABEL_INSET = 8;

    /** One shimmer sweep, in milliseconds. Slow on purpose: this is a wait, not an animation. */
    private static final long SWEEP_MS = 1_200L;

    /** How wide the moving highlight band is, as a fraction of the row. */
    private static final float BAND_FRACTION = 0.22f;

    private SciFiSkeleton() {
    }

    /**
     * Draws placeholder rows filling the given box, top-down, clipped to whole rows.
     *
     * @param x          left edge of the content column
     * @param top        first row's top edge
     * @param width      column width, including the gutter the real rows leave for the star column
     * @param bottom     hard bottom edge; a row that would cross it is not drawn
     * @param rowHeight  the real row pitch, so the swap does not move anything
     * @param rightInset room reserved at the right edge (the favourite-star gutter)
     */
    public static void render(GuiGraphicsExtractor g, int x, int top, int width, int bottom,
                              int rowHeight, int rightInset) {
        if (width <= 0 || rowHeight <= 0 || bottom <= top) {
            return;
        }
        long phase = System.currentTimeMillis() % SWEEP_MS;
        float sweep = phase / (float) SWEEP_MS;

        int cardWidth = Math.max(1, width - rightInset);
        int index = 0;
        for (int y = top; y + rowHeight <= bottom; y += rowHeight, index++) {
            renderRow(g, x, y, cardWidth, rowHeight, index, sweep);
        }
    }

    private static void renderRow(GuiGraphicsExtractor g, int x, int y, int width, int rowHeight,
                                  int index, float sweep) {
        int cardHeight = Math.max(1, rowHeight - 2);
        SciFiRender.roundedRect(g, x, y, width, cardHeight, SBSTheme.CORNER_RADIUS, dim(SBSTheme.CARD_BG, 0.55f));

        int barY = y + (cardHeight - BAR_HEIGHT) / 2;

        // Value pill, hard against the right edge — the real row's value box anchor.
        int pillWidth = Math.min(PILL_WIDTH, Math.max(0, width - LABEL_INSET * 3));
        if (pillWidth > 0) {
            int pillX = x + width - LABEL_INSET - pillWidth;
            bar(g, pillX, barY, pillWidth, sweep, index);
        }

        // Label bar, left. Never allowed to reach the pill: the two overlapping would be the exact
        // collision ui/AGENTS.md forbids on the real row, drawn by the thing standing in for it.
        int labelRoom = Math.max(0, width - LABEL_INSET * 2 - pillWidth - LABEL_INSET);
        int labelWidth = (int) (labelRoom * LABEL_FRACTIONS[Math.floorMod(index, LABEL_FRACTIONS.length)]);
        if (labelWidth > 0) {
            bar(g, x + LABEL_INSET, barY, labelWidth, sweep, index);
        }
    }

    /**
     * One placeholder bar with the highlight band passing through it.
     *
     * <p>The band is positioned per row rather than per screen, and offset by the row index, so the
     * sweep runs diagonally down the list instead of every row flashing in unison.
     */
    private static void bar(GuiGraphicsExtractor g, int x, int y, int width, float sweep, int index) {
        int base = dim(SBSTheme.TEXT_MUTED, 0.22f);
        SciFiRender.roundedRect(g, x, y, width, BAR_HEIGHT, 2, base);

        float offset = sweep - index * 0.06f;
        offset -= Math.floor(offset);
        int bandWidth = Math.max(2, (int) (width * BAND_FRACTION));
        int bandX = x + (int) (offset * (width + bandWidth)) - bandWidth;
        int from = Math.max(x, bandX);
        int to = Math.min(x + width, bandX + bandWidth);
        if (to > from) {
            SciFiRender.roundedRect(g, from, y, to - from, BAR_HEIGHT, 2, dim(SBSTheme.TEXT_MUTED, 0.34f));
        }
    }

    /**
     * Scales a themed colour's alpha, keeping its RGB.
     *
     * <p>Reads the alpha from the theme colour rather than assuming {@code 0xFF}: the surface colours
     * are already translucent in most styles, and multiplying a constant would make the skeleton
     * heavier than the panel it sits on in exactly the styles that are lightest.
     */
    private static int dim(int argb, float factor) {
        int alpha = (int) (((argb >>> 24) & 0xFF) * factor);
        return (Math.max(0, Math.min(255, alpha)) << 24) | (argb & 0x00FFFFFF);
    }
}
