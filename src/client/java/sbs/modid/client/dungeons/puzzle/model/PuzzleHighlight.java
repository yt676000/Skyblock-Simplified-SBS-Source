/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.puzzle.model;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * One thing a solver wants drawn: a box or a line, optionally labelled.
 *
 * <p>Solvers produce these and never draw anything themselves, so the whole feature has exactly one
 * render path to keep correct and one place where "show nothing when not certain" can be enforced -
 * an empty highlight list is the only way a solver expresses uncertainty, which makes the safe state
 * the one that costs no code.
 *
 * @param box    the world-space box to outline
 * @param color  ARGB
 * @param label  floating text above the box, or {@code null} for none
 * @param strong whether this is the answer (thick outline) rather than context (thin)
 * @param lineTo when set, a line from {@code box}'s centre to here is drawn instead of the box, and
 *               the label sits at the line's middle
 */
public record PuzzleHighlight(AABB box, int color, String label, boolean strong, Vec3 lineTo) {

    public static PuzzleHighlight answer(AABB box, int color, String label) {
        return new PuzzleHighlight(box, color, label, true, null);
    }

    public static PuzzleHighlight context(AABB box, int color, String label) {
        return new PuzzleHighlight(box, color, label, false, null);
    }

    /** A thick line from {@code from} to {@code to}, labelled at its middle when {@code label} is set. */
    public static PuzzleHighlight line(Vec3 from, Vec3 to, int color, String label) {
        return new PuzzleHighlight(new AABB(from, from), color, label, true, to);
    }
}
