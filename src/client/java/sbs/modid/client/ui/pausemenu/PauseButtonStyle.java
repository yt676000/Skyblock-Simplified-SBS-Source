/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.pausemenu;

/**
 * Persisted look of one vanilla pause-menu widget: where it sits relative to the position the vanilla
 * layout gave it, how big it is, and how round its corners are. Keyed by
 * {@link PauseMenuStyles#idOf} in {@code gui/pause_menu.json}, so it is a plain mutable POJO Gson can
 * (de)serialize directly.
 *
 * <p>Every field has a "leave it alone" default, so a button nobody touched costs nothing and keeps
 * following vanilla's own layout: {@code dx/dy = 0} (where the layout put it), {@code w/h = 0} (the
 * size the layout gave it) and {@code corner = -1} (the theme's radius). That matters because the
 * vanilla layout is recomputed for every screen size – storing absolute positions would pin the menu
 * to whatever window it was edited in.
 */
public final class PauseButtonStyle {

    /** Smallest usable button; below this the label has nowhere to go. */
    public static final int MIN_WIDTH = 20;
    public static final int MIN_HEIGHT = 8;
    public static final int MAX_WIDTH = 400;
    public static final int MAX_HEIGHT = 80;

    /** Offset from the vanilla layout position, in GUI-scaled pixels. */
    public int dx = 0;
    public int dy = 0;

    /** Size in GUI-scaled pixels; {@code 0} keeps whatever the vanilla layout chose. */
    public int w = 0;
    public int h = 0;

    /** Corner radius in GUI px; {@code -1} uses the SBS theme's default radius. */
    public int corner = -1;

    public boolean isDefault() {
        return dx == 0 && dy == 0 && w == 0 && h == 0 && corner == -1;
    }

    /** The live width for a button the layout sized {@code baseWidth}. */
    public int width(int baseWidth) {
        return w > 0 ? Math.clamp(w, MIN_WIDTH, MAX_WIDTH) : baseWidth;
    }

    /** The live height for a button the layout sized {@code baseHeight}. */
    public int height(int baseHeight) {
        return h > 0 ? Math.clamp(h, MIN_HEIGHT, MAX_HEIGHT) : baseHeight;
    }

    /** Records a resize, clamped – always as explicit values, so it stops tracking the layout size. */
    public void resize(int width, int height) {
        this.w = Math.clamp(width, MIN_WIDTH, MAX_WIDTH);
        this.h = Math.clamp(height, MIN_HEIGHT, MAX_HEIGHT);
    }

    /**
     * Records a corner radius, clamped to the half-height that is the roundest a box can get (beyond
     * it the arcs would overlap and the shape stops changing).
     */
    public void setCorner(int radius, int liveHeight) {
        this.corner = Math.clamp(radius, 0, Math.max(0, liveHeight / 2));
    }
}
