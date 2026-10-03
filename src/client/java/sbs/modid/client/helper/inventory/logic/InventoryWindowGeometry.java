/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.inventory.logic;

/**
 * The pure maths behind Inventory Window: where the window may go, how a point maps between the
 * window's own space and the screen at a given scale, and which memory entry a screen uses.
 *
 * <p>No Minecraft types, so every rule here is unit-tested without a client.
 */
public final class InventoryWindowGeometry {

    /** Height of the title bar drawn above the panel, in GUI pixels. */
    public static final int BAR_HEIGHT = 12;

    /** Smallest and largest scale the window may take, as factors. */
    public static final double MIN_SCALE = 0.5;
    public static final double MAX_SCALE = 2.0;

    /** Memory key of the player inventory. */
    public static final String KEY_INVENTORY = "INVENTORY_WINDOW:inventory";

    /** Memory key shared by every other container (Hypixel menus, chests). */
    public static final String KEY_MENUS = "INVENTORY_WINDOW:menus";

    private InventoryWindowGeometry() {
    }

    /**
     * The top-left corner {@code {x, y}} of a {@code w} x {@code h} window moved as little as
     * possible to lie inside a {@code screenW} x {@code screenH} viewport. A window larger than the
     * viewport is pinned to its top-left edge, so the title bar - the only handle to move it back -
     * is always reachable.
     */
    public static int[] clamp(int x, int y, int w, int h, int screenW, int screenH) {
        int cx = Math.max(0, Math.min(x, screenW - w));
        int cy = Math.max(0, Math.min(y, screenH - h));
        return new int[] {cx, cy};
    }

    /** {@code scale} limited to {@link #MIN_SCALE}..{@link #MAX_SCALE}; NaN becomes 1. */
    public static double clampScale(double scale) {
        if (Double.isNaN(scale)) {
            return 1.0;
        }
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
    }

    /**
     * A window-space coordinate drawn at {@code scale} around {@code anchor}, in screen space - the
     * transform a pose scale around the window's corner applies.
     */
    public static double toScreen(double window, double anchor, double scale) {
        return anchor + (window - anchor) * scale;
    }

    /** The inverse of {@link #toScreen}: where a screen-space mouse coordinate lands in the window. */
    public static double toWindow(double screen, double anchor, double scale) {
        return anchor + (screen - anchor) / scale;
    }

    /** The memory entry a screen is remembered under. */
    public static String memoryKey(boolean playerInventory) {
        return playerInventory ? KEY_INVENTORY : KEY_MENUS;
    }
}
