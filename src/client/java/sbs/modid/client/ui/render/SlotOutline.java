/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The 2px ring the mod draws round a menu slot to say "this one".
 *
 * <p>It exists because there were already three byte-identical copies of it -
 * {@code dungeons/run/ui/LeapMenu}, {@code dungeons/terminal/TerminalSolver} and
 * {@code helper/experiment/logic/ExperimentationTable} - each private to its own feature, and the
 * Chocolate Factory helper was about to be the fourth. That is how the mod ended up with two
 * different sliders: not by anyone deciding to, but one screen at a time.
 *
 * <p>The three existing copies are deliberately <b>not</b> converted here. Root {@code AGENTS.md}
 * keeps refactors and features in separate merges, and two of those three are dungeon files another
 * session may be sitting in. This is the shared home; moving them into it is a change of its own.
 *
 * <p>Coordinates are <b>screen space</b>. A slot's own {@code x}/{@code y} are relative to the GUI
 * origin, so callers add {@code leftPos}/{@code topPos} first - passing the raw slot coordinates
 * draws the ring in the top-left corner of the window, which is the mistake this note exists to
 * stop being made a fourth time.
 */
public final class SlotOutline {

    /** A vanilla inventory slot is 16px of item with a 1px gap around it. */
    private static final int SLOT = 16;

    private SlotOutline() {
    }

    /**
     * Draws the ring hugging a 16px slot whose top-left item pixel is at {@code (x, y)}.
     *
     * @param color ARGB; alpha is honoured, so a faint marker is a lower alpha rather than a
     *              different drawing routine
     */
    public static void draw(GuiGraphicsExtractor g, int x, int y, int color) {
        g.fill(x - 1, y - 1, x + SLOT + 1, y + 1, color);                    // top
        g.fill(x - 1, y + SLOT - 1, x + SLOT + 1, y + SLOT + 1, color);      // bottom
        g.fill(x - 1, y - 1, x + 1, y + SLOT + 1, color);                    // left
        g.fill(x + SLOT - 1, y - 1, x + SLOT + 1, y + SLOT + 1, color);      // right
    }
}
