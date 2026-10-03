/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */
package sbs.modid.client.helper.visual.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The animated chroma frame drawn around a slot that wants the player's attention - a claimable
 * Bazaar order, the SkyBlock button in the lobby's Game Menu.
 *
 * <p>One implementation so every framed slot looks and moves the same. It used to live privately in
 * the Bazaar code with its own rainbow and its own rate; it now goes through {@link Chroma}, so it
 * follows the Theme's chroma palette and speed like every other chroma effect in the mod.
 */
public final class ChromaBorder {

    /** Ring thickness in pixels, outside the 1px slot gap. */
    private static final int THICKNESS = 2;

    private ChromaBorder() {
    }

    /**
     * Draws the frame around a 16x16 slot whose top-left is {@code (slotX, slotY)}, in whatever
     * space the caller is drawing slots in. The colour runs diagonally from top-left to bottom-right
     * and the phase comes from the wall clock, so every framed slot on screen animates in step.
     */
    public static void draw(GuiGraphicsExtractor g, int slotX, int slotY) {
        int x = slotX - 1;
        int y = slotY - 1;
        int size = 16 + 2; // slot + 1px on each side
        double phase = Chroma.phase(Chroma.speed()); // once per slot, not once per pixel
        int span = Math.max(1, (size + size) - 2); // max diagonal distance (x+y)

        for (int t = 0; t < THICKNESS; t++) {
            int left = x - t;
            int top = y - t;
            int w = size + t * 2;
            int h = size + t * 2;
            // Walk the perimeter of this ring, colouring each pixel by its diagonal position.
            for (int px = 0; px < w; px++) {
                putPixel(g, left + px, top, px, 0, span, phase);                 // top edge
                putPixel(g, left + px, top + h - 1, px, h - 1, span, phase);     // bottom edge
            }
            for (int py = 0; py < h; py++) {
                putPixel(g, left, top + py, 0, py, span, phase);                 // left edge
                putPixel(g, left + w - 1, top + py, w - 1, py, span, phase);     // right edge
            }
        }
    }

    private static void putPixel(GuiGraphicsExtractor g, int screenX, int screenY,
                                 int localX, int localY, int span, double phase) {
        double diagonal = (double) (localX + localY) / span; // 0 at top-left -> 1 at bottom-right
        int rgb = Chroma.vividRgb(Chroma.rampRgb(diagonal - phase));
        g.fill(screenX, screenY, screenX + 1, screenY + 1, 0xFF000000 | rgb);
    }
}
