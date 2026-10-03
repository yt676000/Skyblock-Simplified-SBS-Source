/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.forge.command;

import net.minecraft.client.Minecraft;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.economy.forge.ui.ForgeFlipsScreen;
import sbs.modid.client.helper.warp.WarpMenuScreen;

/**
 * The in-world hotkeys of the Forge Flips window and the custom Warp Menu.
 *
 * <p>Dispatched from {@code CommandKeyMixin}, which only fires on a fresh press with no screen open –
 * the same gate every other SBS hotkey uses, so these can never fire while typing in chat.
 */
public final class ForgeKeybinds {

    private ForgeKeybinds() {
    }

    /** Called for every fresh in-world key press. */
    public static void onKeyPressed(int keyCode) {
        var config = ConfigManager.getInstance().get();
        if (keyCode != 0 && keyCode == config.forge.openKey) {
            Minecraft.getInstance().setScreenAndShow(new ForgeFlipsScreen());
            return;
        }
        if (keyCode != 0 && keyCode == config.warpMenu.openKey) {
            Minecraft.getInstance().setScreenAndShow(new WarpMenuScreen());
            return;
        }
        if (keyCode != 0 && keyCode == config.minionCalc.openKey && config.minionCalc.enabled) {
            Minecraft.getInstance().setScreenAndShow(
                    new sbs.modid.client.economy.minions.ui.MinionCalcScreen());
        }
    }
}
