/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.command;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

/**
 * Build Tools' bindable keys, dispatched from {@code KeybindDispatch} on fresh in-world presses.
 *
 * <p>The corner keys are how a selection is made on a server, where the Magic Stick Thingy cannot be
 * given: they read the block the crosshair is on and record it - nothing is clicked or sent. All
 * unbound ({@code 0}) by default and inert while the module is off.
 */
public final class BuildKeybinds {

    /** Opens the Quick Paste grid; installed by the screen, so this class does not name it. */
    private static volatile Runnable quickPaste;

    private BuildKeybinds() {
    }

    public static void setQuickPaste(Runnable opener) {
        quickPaste = opener;
    }

    public static void onKeyPressed(int keyCode) {
        if (keyCode == 0) {
            return;
        }
        SBSConfig.BuildToolsSettings cfg = ConfigManager.getInstance().get().buildTools;
        if (!cfg.enabled) {
            return;
        }
        if (sbs.modid.client.helper.build.model.SelectionUx.clearKeyActive(cfg.clearSelectionKey, keyCode,
                sbs.modid.client.helper.build.logic.MagicStickInput.holding())) {
            sbs.modid.client.helper.build.logic.SelectionActions.clear();
            return;
        }
        if (keyCode == cfg.corner1Key || keyCode == cfg.corner2Key || keyCode == cfg.libraryKey
                || keyCode == cfg.quickPasteKey || keyCode == cfg.freecamKey) {
            sbs.modid.client.helper.build.logic.SelectionActions.touch();
        }
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (keyCode == cfg.corner2Key && player != null && sbs.modid.client.helper.build.model.SelectionUx
                .gestureClears(player.isShiftKeyDown(), sbs.modid.client.helper.build.logic.BuildTargeting.target() != null)) {
            // The stick's gesture on the key, for servers where there is no stick: Sneak + key at air clears.
            sbs.modid.client.helper.build.logic.SelectionActions.clear();
            return;
        }
        if (keyCode == cfg.corner1Key) {
            BuildCommands.corner(true);
        } else if (keyCode == cfg.corner2Key) {
            BuildCommands.corner(false);
        } else if (keyCode == cfg.libraryKey) {
            BuildCommands.openLibrary();
        } else if (keyCode == cfg.freecamKey) {
            sbs.modid.client.helper.build.logic.Freecam.toggle();
        } else if (keyCode == cfg.quickPasteKey && quickPaste != null) {
            quickPaste.run();
        }
    }
}
