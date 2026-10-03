/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.playerviewer.ui;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.Font;

/**
 * Everything a {@link PvPage} needs for one frame: the profile to draw, the content rectangle the
 * shell reserved for it, and the mouse. Pages never touch the screen's geometry themselves, so the
 * shell stays the single owner of the layout and no page can draw over the navigation.
 *
 * <p>Scrolling is cooperative: the shell passes the current {@link #scroll} in, the page reports how
 * many rows it actually has via {@link #rows(int)}, and the shell clamps the wheel against that.
 * A page that never calls {@code rows} simply does not scroll.
 */
public final class PvContext {

    public final Font font;
    /** The selected profile's view object from {@code /api/pv}. Never null while a page renders. */
    public final JsonObject profile;
    /** The full payload ({@code name}, {@code uuid}, {@code profiles}). */
    public final JsonObject root;

    /** The content rectangle: everything left of the recent strip and right of the sidebar. */
    public final int x;
    public final int y;
    public final int width;
    public final int height;

    public final int mouseX;
    public final int mouseY;
    public final int scroll;

    /** Highest first-row index the shell may scroll to; written by the page via {@link #rows(int)}. */
    private int scrollMax;

    public PvContext(Font font, JsonObject root, JsonObject profile,
                     int x, int y, int width, int height, int mouseX, int mouseY, int scroll) {
        this.font = font;
        this.root = root;
        this.profile = profile;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        this.scroll = scroll;
    }

    /** The y just past the content area – pages must not draw at or below this. */
    public int bottom() {
        return y + height;
    }

    /** The x just past the content area. */
    public int right() {
        return x + width;
    }

    /**
     * Declares that the page has {@code total} rows of which {@code visible} fit, and returns the
     * clamped first-row index to start drawing at. Also records the scroll ceiling for the shell.
     */
    public int rows(int total, int visible) {
        scrollMax = Math.max(0, total - visible);
        return Math.max(0, Math.min(scroll, scrollMax));
    }

    public int scrollMax() {
        return scrollMax;
    }

    public boolean hovered(int rx, int ry, int rw, int rh) {
        return mouseX >= rx && mouseX < rx + rw && mouseY >= ry && mouseY < ry + rh;
    }
}
