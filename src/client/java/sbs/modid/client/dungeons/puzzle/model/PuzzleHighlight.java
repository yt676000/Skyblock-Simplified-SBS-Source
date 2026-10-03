/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.puzzle.model;

import net.minecraft.world.phys.AABB;

/**
 * One thing a solver wants drawn: a box, optionally labelled.
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
 */
public record PuzzleHighlight(AABB box, int color, String label, boolean strong) {

    public static PuzzleHighlight answer(AABB box, int color, String label) {
        return new PuzzleHighlight(box, color, label, true);
    }

    public static PuzzleHighlight context(AABB box, int color, String label) {
        return new PuzzleHighlight(box, color, label, false);
    }
}
