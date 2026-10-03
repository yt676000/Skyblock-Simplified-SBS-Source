/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.garden.command;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.skills.garden.GardenPlotsModule;

/**
 * The Garden Plots hotkeys, dispatched from {@code CommandKeyMixin} on a fresh key press while
 * in-world with no screen open (both unbound by default).
 *
 * <p>That dispatch point is what keeps these out of the way of typing: it fires only on
 * {@code GLFW_PRESS}, only with a player and level, and only while no screen is open - so chat, a
 * sign, the settings UI and every container are already excluded before anything here runs.
 */
public final class GardenPlotsKeybinds {

    private GardenPlotsKeybinds() {
    }

    public static void onKeyPressed(int keyCode) {
        var cfg = ConfigManager.getInstance().get().gardenPlots;
        if (!cfg.enabled) {
            return;
        }
        if (cfg.openKey != 0 && keyCode == cfg.openKey) {
            GardenPlotsModule.openScreen();
            return;
        }
        if (cfg.infestedKey != 0 && keyCode == cfg.infestedKey) {
            sbs.modid.client.skills.garden.logic.InfestedPlotWarp.onKeyPressed();
        }
    }
}
