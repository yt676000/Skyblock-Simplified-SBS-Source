/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.window;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import sbs.modid.client.ui.theme.SBSTheme;

/**
 * Shared edge/corner resize behaviour for the floating SBS windows – the mechanics of the Item
 * Price History window (grab any of the 8 edges/corners, Windows-style, with corner L-grips that
 * light up on hover), reusable by every other window so all of them feel identical.
 *
 * <p>Usage: {@code begin()} on mouse-down (returns true when an edge zone was hit), {@code drag()}
 * per mouse-drag (returns the new {@code {x, y, w, h}}), {@code end()} on release;
 * {@code renderGrips()} draws the four corner indicators.
 */
public final class WindowResizer {

    /** Width of the invisible resize grab zones along the window edges (GUI pixels). */
    public static final int GRIP = 5;

    /** Size of the visual corner grip indicator (small L-shape). */
    private static final int GRIP_VISUAL = 8;

    public enum Dir {
        NONE,
        N, S, E, W,
        NE, NW, SE, SW;

        boolean hasNorth() {
            return this == N || this == NE || this == NW;
        }

        boolean hasSouth() {
            return this == S || this == SE || this == SW;
        }

        boolean hasEast() {
            return this == E || this == NE || this == SE;
        }

        boolean hasWest() {
            return this == W || this == NW || this == SW;
        }
    }

    private Dir active = Dir.NONE;
    private int startMouseX;
    private int startMouseY;
    private int startX;
    private int startY;
    private int startW;
    private int startH;

    /** The resize direction the mouse is hovering over ({@link Dir#NONE} outside the edge zones). */
    public Dir hitTest(double mx, double my, int x, int y, int w, int h) {
        if (mx < x || mx >= x + w || my < y || my >= y + h) {
            return Dir.NONE;
        }
        boolean nearLeft = mx < x + GRIP;
        boolean nearRight = mx >= x + w - GRIP;
        boolean nearTop = my < y + GRIP;
        boolean nearBottom = my >= y + h - GRIP;

        if (nearTop && nearLeft) {
            return Dir.NW;
        }
        if (nearTop && nearRight) {
            return Dir.NE;
        }
        if (nearBottom && nearLeft) {
            return Dir.SW;
        }
        if (nearBottom && nearRight) {
            return Dir.SE;
        }
        if (nearTop) {
            return Dir.N;
        }
        if (nearBottom) {
            return Dir.S;
        }
        if (nearLeft) {
            return Dir.W;
        }
        if (nearRight) {
            return Dir.E;
        }
        return Dir.NONE;
    }

    /** Starts a resize when the click hits an edge zone; remembers the drag origin. */
    public boolean begin(double mx, double my, int x, int y, int w, int h) {
        Dir dir = hitTest(mx, my, x, y, w, h);
        if (dir == Dir.NONE) {
            return false;
        }
        active = dir;
        startMouseX = (int) mx;
        startMouseY = (int) my;
        startX = x;
        startY = y;
        startW = w;
        startH = h;
        return true;
    }

    public boolean isActive() {
        return active != Dir.NONE;
    }

    /** The window rect {@code {x, y, w, h}} for the current drag position, size-clamped. */
    public int[] drag(double mx, double my, int minW, int maxW, int minH, int maxH) {
        int dx = (int) mx - startMouseX;
        int dy = (int) my - startMouseY;
        int newX = startX;
        int newY = startY;
        int newW = startW;
        int newH = startH;
        if (active.hasEast()) {
            newW = clamp(startW + dx, minW, maxW);
        }
        if (active.hasWest()) {
            int wantW = clamp(startW - dx, minW, maxW);
            newX = startX + (startW - wantW);
            newW = wantW;
        }
        if (active.hasSouth()) {
            newH = clamp(startH + dy, minH, maxH);
        }
        if (active.hasNorth()) {
            int wantH = clamp(startH - dy, minH, maxH);
            newY = startY + (startH - wantH);
            newH = wantH;
        }
        return new int[]{newX, newY, newW, newH};
    }

    /** Ends the resize; returns true when one was active (the release is then consumed). */
    public boolean end() {
        boolean wasActive = active != Dir.NONE;
        active = Dir.NONE;
        return wasActive;
    }

    /** The four corner L-grips, brightened on the hovered/active direction. */
    public void renderGrips(GuiGraphicsExtractor g, int x, int y, int w, int h,
                            double mouseX, double mouseY) {
        Dir dir = active != Dir.NONE ? active : hitTest(mouseX, mouseY, x, y, w, h);
        int bright = SBSTheme.ACCENT;
        int dim = 0x44FFFFFF;
        int gs = GRIP_VISUAL;

        int c = (dir == Dir.NW || dir == Dir.N || dir == Dir.W) ? bright : dim;
        g.fill(x - 1, y - 1, x - 1 + gs, y + 1, c);
        g.fill(x - 1, y - 1, x + 1, y - 1 + gs, c);

        c = (dir == Dir.NE || dir == Dir.N || dir == Dir.E) ? bright : dim;
        g.fill(x + w + 1 - gs, y - 1, x + w + 1, y + 1, c);
        g.fill(x + w - 1, y - 1, x + w + 1, y - 1 + gs, c);

        c = (dir == Dir.SW || dir == Dir.S || dir == Dir.W) ? bright : dim;
        g.fill(x - 1, y + h - 1, x - 1 + gs, y + h + 1, c);
        g.fill(x - 1, y + h + 1 - gs, x + 1, y + h + 1, c);

        c = (dir == Dir.SE || dir == Dir.S || dir == Dir.E) ? bright : dim;
        g.fill(x + w + 1 - gs, y + h - 1, x + w + 1, y + h + 1, c);
        g.fill(x + w - 1, y + h + 1 - gs, x + w + 1, y + h + 1, c);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
