/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.garden.command;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.skills.garden.logic.GardenBlueprintManager;

/**
 * Garden Blueprint keys, dispatched from {@code CommandKeyMixin} on fresh in-world key presses (no
 * screen open) – the same gate the other module keybinds use.
 *
 * <p>Freely configurable keys: one copies (the plot underfoot, or the custom-area selection when
 * that mode is on), one shows / hides the ghost preview, one pins the copied blueprint to the block
 * you look at, and one sets the selection corners. All are inert while the module is off or unbound
 * ({@code 0}).
 */
public final class GardenBlueprintKeybinds {

    private GardenBlueprintKeybinds() {
    }

    /** Called for every fresh in-world key press; runs the matching Garden Blueprint action. */
    public static void onKeyPressed(int keyCode) {
        if (keyCode == 0) {
            return;
        }
        SBSConfig.GardenBlueprintSettings cfg = ConfigManager.getInstance().get().gardenBlueprint;
        if (!cfg.enabled) {
            return;
        }
        if (keyCode == cfg.copyKey) {
            GardenBlueprintManager.getInstance().copy();
        } else if (keyCode == cfg.toggleKey) {
            GardenBlueprintManager.getInstance().togglePreview();
        } else if (keyCode == cfg.placeKey) {
            GardenBlueprintManager.getInstance().placeAtLook();
        } else if (cfg.customArea && keyCode == cfg.cornerKey) {
            GardenBlueprintManager.getInstance().setCorner();
        }
    }
}
