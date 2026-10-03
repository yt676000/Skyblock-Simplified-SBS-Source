/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.itemprotection.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The shield drawn over a protected slot.
 *
 * <p>Plain {@code fill} rectangles rather than a texture, for the same reasons the slot-lock padlock
 * is: no asset to ship, scales with the GUI, legible at 16x16 where a sprite blurs.
 *
 * <p><b>Top-left, and that is not a style choice.</b> The stack count sits bottom-right and the slot
 * lock's padlock is already there; a second marker in that corner would cover one or hide under the
 * other. Two features that both mean "you cannot move this" must still be tellable apart at a
 * glance, so they take opposite corners and different shapes.
 */
public final class ProtectedItemIcon {

    private ProtectedItemIcon() {
    }

    /**
     * Draws a 5x6 shield at the top-left of the 16x16 slot at {@code (x, y)}, in slot-relative
     * space - the space {@code extractSlots} already runs in.
     *
     * @param color ARGB body colour; the outline is derived from it so one config value is enough
     */
    public static void draw(GuiGraphicsExtractor g, int x, int y, int color) {
        int outline = 0xFF000000;   // a dark keyline, so the shield reads on a pale item too

        // Outline first, then the body one pixel inside it.
        g.fill(x, y, x + 7, y + 6, outline);
        g.fill(x + 1, y + 6, x + 6, y + 7, outline);
        g.fill(x + 2, y + 7, x + 5, y + 8, outline);

        g.fill(x + 1, y + 1, x + 6, y + 5, color);
        g.fill(x + 2, y + 5, x + 5, y + 6, color);
        g.fill(x + 3, y + 6, x + 4, y + 7, color);
    }
}
