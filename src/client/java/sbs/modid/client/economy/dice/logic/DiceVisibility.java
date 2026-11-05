/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.dice.logic;

/** When the Dice Profit card is on screen: pure, so the rule is testable without a client. */
public final class DiceVisibility {

    private DiceVisibility() {
    }

    /**
     * @param onlyAfterRoll the "Only Show After A Roll" setting
     * @param showSeconds   how long after a roll the card stays
     * @param lastRollAt    {@code System.currentTimeMillis()} of the last roll, 0 if none yet
     * @param now           the current time in the same clock
     * @param editorOpen    the HUD editor is open - always shown there so it can be placed
     */
    public static boolean shown(boolean onlyAfterRoll, int showSeconds, long lastRollAt, long now,
                                boolean editorOpen) {
        if (editorOpen || !onlyAfterRoll) {
            return true;
        }
        return lastRollAt > 0 && now - lastRollAt <= showSeconds * 1000L;
    }
}
