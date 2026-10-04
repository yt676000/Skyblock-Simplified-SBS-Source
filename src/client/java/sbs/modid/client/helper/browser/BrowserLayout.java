/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.browser;

/**
 * The browser window's geometry, shared by {@link BrowserScreen} and the in-world HUD overlay so the
 * page sits on the same pixels in both: opening or closing the screen neither moves nor resizes it
 * (a resize would make Chromium re-lay out the page).
 *
 * <p>One model: the saved rectangle is the <b>window</b> - toolbar included. The page is the part
 * below the toolbar and its 1 px accent line. The page used to be drawn over the whole window with
 * the toolbar on top, which hid the top {@value #TOOLBAR_H} + 1 px of every site.
 */
final class BrowserLayout {

    static final int TOOLBAR_H = 18;
    /** The accent line under the toolbar. */
    static final int SEPARATOR = 1;
    static final int MIN_W = 200;
    static final int MIN_H = 120;
    /** The smallest page height a window can have. */
    static final int MIN_CONTENT_H = MIN_H - TOOLBAR_H - SEPARATOR;

    private BrowserLayout() {
    }

    /**
     * The window rectangle for a saved one on a {@code guiW} x {@code guiH} screen: at least the
     * minimum size, no bigger than the screen, and moved back onto it. {@code {x, y, w, h}}.
     */
    static int[] window(int x, int y, int w, int h, int guiW, int guiH) {
        int rw = clamp(w, MIN_W, guiW);
        int rh = clamp(h, MIN_H, guiH);
        int rx = clamp(x, 0, guiW - rw);
        int ry = clamp(y, 0, guiH - rh);
        return new int[] {rx, ry, rw, rh};
    }

    /** The page inside a window: below the toolbar and its line, never shorter than 1 px. */
    static int[] content(int x, int y, int w, int h) {
        int top = TOOLBAR_H + SEPARATOR;
        return new int[] {x, y + top, w, Math.max(1, h - top)};
    }

    /** {@code value} within [min, max]; {@code min} wins when the screen is smaller than the minimum. */
    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
