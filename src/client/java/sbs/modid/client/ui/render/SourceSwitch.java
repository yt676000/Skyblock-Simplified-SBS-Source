/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.render;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The <b>Server / Local</b> switch drawn in the floating flip windows: two chips sharing one frame,
 * exactly one lit.
 *
 * <p><b>Why not a one-click toggle.</b> {@code ui/AGENTS.md} allows click-to-cycle at two options,
 * but a cycle button shows the current value and hides the other one — and here the other one is the
 * whole point. A player looking at a suspicious number needs to see at a glance both which engine
 * produced it and that there is another to compare against. Both labels are on screen, one is lit.
 *
 * <p>A painter and a hit test, not an {@code AbstractWidget}: these windows are drawn by hand into a
 * container screen's render pass and route their own clicks, so there is no widget list to add to.
 * {@link sbs.modid.client.ui.component.SciFiSegmentedSwitch} is the same control for real screens.
 *
 * <p>Every geometry answer comes from {@link #width} and {@link #HEIGHT} so the caller lays out
 * around what will actually be drawn — the labels are font-measured, and a hard-coded box is a box
 * that overlaps at some GUI scale.
 */
public final class SourceSwitch {

    /** Left chip: the licence-backed ranking. Right chip: computed on this machine. */
    private static final String[] LABELS = {"Server", "Local"};

    /** The control's height, matching the small rows these windows use. */
    public static final int HEIGHT = ChipSwitch.HEIGHT;

    private SourceSwitch() {
    }

    /** The width the control needs at this font. Ask before laying the row out, never after. */
    public static int width(Font font) {
        return ChipSwitch.width(font, LABELS);
    }

    /**
     * Draws the switch at {@code (x, y)} - {@link ChipSwitch} with the two labels, so it looks exactly
     * as it did when it had its own painter.
     *
     * @param local which chip is lit
     * @return the width consumed, matching {@link #width}
     */
    public static int draw(GuiGraphicsExtractor g, Font font, int x, int y, boolean local,
                           int mouseX, int mouseY) {
        return ChipSwitch.draw(g, font, x, y, local ? 1 : 0, mouseX, mouseY, LABELS);
    }

    /**
     * Which chip a click landed on.
     *
     * @return {@code TRUE} for Local, {@code FALSE} for Server, {@code null} when the click missed
     *         the control entirely - three answers, because "missed" must not be confused with
     *         "chose the left one"
     */
    public static Boolean hit(Font font, int x, int y, double mouseX, double mouseY) {
        int index = ChipSwitch.hit(font, x, y, mouseX, mouseY, LABELS);
        return index < 0 ? null : index == 1;
    }

    /** The tooltip for the control, so all four windows explain the choice the same way. */
    public static java.util.List<String> tooltip(boolean local) {
        return java.util.List.of(
                local ? "§eShowing the local ranking" : "§aShowing the server ranking",
                "§7Server §8- ranked by us, with price history. Needs a licence token.",
                "§7Local §8- worked out on your machine from live prices only.",
                "",
                "§8The server ranking is still used automatically if you pick it and",
                "§8it is unavailable - you get the local one with a note saying why.");
    }
}
