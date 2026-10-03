/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.inventory.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import sbs.modid.client.ui.theme.SBSTheme;

/**
 * The padlock marker drawn over a locked slot, shared by the container screens and the HUD hotbar so
 * a locked slot looks identical everywhere.
 *
 * <p>Drawn from plain {@code fill} rectangles rather than a texture: it needs no asset, scales with
 * the GUI, and stays legible at 16x16 where a sprite would blur.
 */
public final class SlotLockIcon {

    /** Slot size in GUI pixels. */
    private static final int SLOT = 16;

    /** Dim wash over the slot, so a locked slot reads as locked even at a glance. */
    private static final int TINT = 0x40000000;

    private SlotLockIcon() {
    }

    /**
     * Draws the marker over the 16x16 slot whose top-left corner is {@code (x, y)}: a subtle tint
     * plus a small padlock tucked into the bottom-right, clear of the item's stack-count text.
     */
    public static void draw(GuiGraphicsExtractor g, int x, int y) {
        g.fill(x, y, x + SLOT, y + SLOT, TINT);
        drawPadlock(g, x + SLOT - 7, y + SLOT - 8);
    }

    /** A 6x7 padlock: shackle on top, body below, keyhole in the middle. */
    private static void drawPadlock(GuiGraphicsExtractor g, int x, int y) {
        int body = SBSTheme.ACCENT_BRIGHT;
        int shackle = SBSTheme.ACCENT;

        // Shackle: top bar plus the two legs down into the body.
        g.fill(x + 2, y, x + 5, y + 1, shackle);
        g.fill(x + 1, y + 1, x + 2, y + 3, shackle);
        g.fill(x + 5, y + 1, x + 6, y + 3, shackle);

        // Body with a 1px dark keyhole.
        g.fill(x, y + 3, x + 7, y + 8, body);
        g.fill(x + 3, y + 5, x + 4, y + 7, 0xFF102A44);
    }
}
