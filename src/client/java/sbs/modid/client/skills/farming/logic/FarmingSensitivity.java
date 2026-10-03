/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.farming.logic;

import net.minecraft.client.Minecraft;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.skills.farming.model.FarmingItems;

/**
 * Farming module: <b>reduced mouse sensitivity while holding a farming tool</b>, for the fine aim
 * that crop rows need, without having to change the sensitivity by hand every time.
 *
 * <p><b>Why this is derived, not stored.</b> The obvious implementation – overwrite
 * {@code Options.sensitivity} on equip and restore it on unequip – can strand the reduced value in
 * {@code options.txt} forever if the game exits, crashes or disconnects while the tool is held.
 * Instead {@code MouseSensitivityMixin} asks {@link #apply} for the sensitivity every time
 * {@code MouseHandler.turnPlayer} reads it, and this class recomputes the answer from the live held
 * item. The player's saved setting is never written to, so "back to the original value" is not a
 * step that can be missed – it is simply what happens the moment the tool is gone.
 *
 * <p>The slider is the sensitivity to use while farming, as a percentage of the player's normal
 * setting: 100% is no reduction, 25% is a quarter as fast.
 */
public final class FarmingSensitivity {

    /** Bounds of the configurable reduction, in percent of the player's normal sensitivity.
     *  1% is a near-standstill for row-perfect farming - the old floor of 5% was still too fast. */
    public static final int MIN_PERCENT = 1;
    public static final int MAX_PERCENT = 100;

    private FarmingSensitivity() {
    }

    /** Whether the reduction applies right now: enabled AND a farming tool in the main hand. */
    public static boolean active() {
        SBSConfig.FarmingSettings cfg = ConfigManager.getInstance().get().farming;
        if (!cfg.reduceSensitivity || !sbs.modid.client.skills.SkillIslands.farmingAllowed()) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player != null && FarmingItems.isFarmingTool(minecraft.player.getMainHandItem());
    }

    /**
     * The sensitivity {@code turnPlayer} should use: scaled while a farming tool is held, and the
     * player's own value untouched otherwise.
     *
     * @param sensitivity the value vanilla just read from the options
     */
    public static double apply(double sensitivity) {
        if (!active()) {
            return sensitivity;
        }
        SBSConfig.FarmingSettings cfg = ConfigManager.getInstance().get().farming;
        int pct = Math.max(MIN_PERCENT, Math.min(MAX_PERCENT, cfg.sensitivityPercent));
        return sensitivity * (pct / 100.0);
    }
}
