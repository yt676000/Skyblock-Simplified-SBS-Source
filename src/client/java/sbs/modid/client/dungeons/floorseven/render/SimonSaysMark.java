/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.floorseven.render;

/**
 * Which mark a Simon Says step gets, in the shared mark language: filled glow = do this, hollow
 * ring = next, corner badge = the count for everything after.
 *
 * <p>Kept local to this feature for now; the mod-wide mark helper is being built on another branch
 * and this should fold into it (see the spec).
 */
public enum SimonSaysMark {
    /** Already done - drawn as nothing. Unused until a press-accept signal is confirmed. */
    DONE,
    /** Press this one now: filled glow plus the number. */
    DO_THIS,
    /** The one after: hollow ring plus the number. */
    NEXT,
    /** Everything later: a small number in the corner, softer colour. */
    COUNT;

    /**
     * @param step 0-based position in the recorded order
     * @param next 0-based position of the step to press now (0 while no accept signal exists)
     */
    public static SimonSaysMark of(int step, int next) {
        if (step < next) {
            return DONE;
        }
        if (step == next) {
            return DO_THIS;
        }
        return step == next + 1 ? NEXT : COUNT;
    }
}
