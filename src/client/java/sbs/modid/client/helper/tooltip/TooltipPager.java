/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.tooltip;

/**
 * Pure page maths for {@link ScrollableTooltips}: given the height of every lore line and the
 * height budget, which slice {@code [from, to)} is drawn and how far the offset may go.
 *
 * <p>The page is always sized from the offset it is drawn at, and the last offset is found by
 * fitting lines backwards from the end. Sizing one page from the <i>first</i> lines and reusing
 * that count everywhere was the old bug: the bottom page then held a different number of lines
 * than fit, so the clamp and the drawn page disagreed at the end of the lore.
 */
public final class TooltipPager {

    /** One drawn page: lines {@code [from, to)} of the full lore, plus the clamped offset. */
    public record Page(int from, int to, int maxOffset) {
        public boolean moreAbove() {
            return from > 0;
        }
    }

    private TooltipPager() {
    }

    /**
     * @param heights        height of each line, in order
     * @param budget         total height the page may use, indicator lines included
     * @param indicatorHeight height of one {@code ▲/▼ more} line
     * @param offset         requested offset (lines scrolled off the top), clamped here
     */
    public static Page page(int[] heights, int budget, int indicatorHeight, int offset) {
        int total = heights.length;
        int maxOffset = maxOffset(heights, budget, indicatorHeight);
        int from = Math.max(0, Math.min(maxOffset, offset));
        // Room for the ▲ line when something is above, and for ▼ unless this page reaches the end.
        int room = budget - (from > 0 ? indicatorHeight : 0);
        int to = fitForward(heights, from, room);
        if (to < total) {
            to = Math.max(from + 1, fitForward(heights, from, room - indicatorHeight));
        }
        return new Page(from, to, maxOffset);
    }

    /** Smallest offset whose page reaches the last line (with the ▲ line above it). */
    static int maxOffset(int[] heights, int budget, int indicatorHeight) {
        int total = heights.length;
        if (fitForward(heights, 0, budget) >= total) {
            return 0;
        }
        int room = budget - indicatorHeight;
        int used = 0;
        int from = total;
        while (from > 0 && used + heights[from - 1] <= room) {
            used += heights[--from];
        }
        return Math.min(total - 1, Math.max(1, from));
    }

    private static int fitForward(int[] heights, int from, int room) {
        int used = 0;
        int i = from;
        while (i < heights.length && used + heights[i] <= room) {
            used += heights[i++];
        }
        return i;
    }
}
