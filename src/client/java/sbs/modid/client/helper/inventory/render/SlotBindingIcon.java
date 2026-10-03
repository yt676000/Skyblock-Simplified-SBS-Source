/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.inventory.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.ui.theme.SBSTheme;

/**
 * The marker on a bound slot: a thin frame plus the binding's number, so the two ends of one binding
 * can be told apart from the two ends of another.
 *
 * <p>The number is the reason this is not just a tint. Several bindings are on screen at once and
 * they all mean "shift-click swaps this", so colour alone could not say <i>with what</i> — and
 * {@code ui/AGENTS.md} does not allow state carried by colour alone in any case. The anchor wears a
 * filled tag and a member an outlined one, which is the same distinction again without a second
 * colour.
 *
 * <p>Drawn from {@code fill} rectangles and one glyph rather than a texture, exactly like
 * {@link SlotLockIcon}, so the two markers sit on the same slot without either needing an asset.
 */
public final class SlotBindingIcon {

    /** Slot size in GUI pixels. */
    private static final int SLOT = 16;

    /** Corner tag size, big enough for a digit and clear of the stack count in the other corner. */
    private static final int TAG = 8;

    private SlotBindingIcon() {
    }

    /**
     * Draws the marker over the 16x16 slot whose top-left corner is {@code (x, y)}.
     *
     * @param number   the binding's 1-based number, shown in the tag
     * @param isAnchor whether this is the hotbar / offhand end (filled tag) or a member (outlined)
     */
    public static void draw(GuiGraphicsExtractor g, int x, int y, int number, boolean isAnchor) {
        frame(g, x, y, SBSTheme.ACCENT);
        tag(g, x, y, number, isAnchor);
    }

    /**
     * Draws the "waiting for its partner" marker: the same frame in the warning colour, so the slot
     * picked first is visibly different from one that is already bound.
     */
    public static void drawPending(GuiGraphicsExtractor g, int x, int y) {
        frame(g, x, y, SBSTheme.WARN);
    }

    /** A 1px frame just inside the slot, which leaves the item itself unobscured. */
    private static void frame(GuiGraphicsExtractor g, int x, int y, int color) {
        g.fill(x, y, x + SLOT, y + 1, color);
        g.fill(x, y + SLOT - 1, x + SLOT, y + SLOT, color);
        g.fill(x, y + 1, x + 1, y + SLOT - 1, color);
        g.fill(x + SLOT - 1, y + 1, x + SLOT, y + SLOT - 1, color);
    }

    /**
     * The number tag, top-left. Bindings past nine show a dot rather than a two-digit number that
     * would not fit the tag — the frame still says the slot is bound, and nobody keeps ten bindings
     * apart by their numbers anyway.
     */
    private static void tag(GuiGraphicsExtractor g, int x, int y, int number, boolean isAnchor) {
        int fill = isAnchor ? SBSTheme.ACCENT : 0xE0102A44;
        g.fill(x + 1, y + 1, x + 1 + TAG, y + 1 + TAG, fill);
        if (number < 1 || number > 9) {
            g.fill(x + 4, y + 4, x + 6, y + 6, SBSTheme.TEXT);
            return;
        }
        var font = Minecraft.getInstance().font;
        g.text(font, Component.literal(String.valueOf(number)), x + 3, y + 2,
                isAnchor ? 0xFF102A44 : SBSTheme.ACCENT_BRIGHT);
    }
}
