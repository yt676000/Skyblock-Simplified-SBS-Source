/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.secretroutes.command;

import net.minecraft.client.Minecraft;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.dungeons.secretroutes.logic.SecretRoutesManager;

/**
 * Secret Routes hotkeys, dispatched from {@code CommandKeyMixin} on a fresh key press while in-world
 * with no screen open: place the four waypoint types at the player, or open the editor. All unbound
 * by default and a no-op unless the module is enabled and a room is recognised (the manager reports
 * "No room detected" itself when it is not).
 */
public final class SecretRoutesKeybinds {

    private SecretRoutesKeybinds() {
    }

    public static void onKeyPressed(int keyCode) {
        SBSConfig.SecretRoutesSettings cfg = SecretRoutesManager.cfg();
        if (!cfg.enabled || keyCode == 0) {
            return;
        }
        SecretRoutesManager manager = SecretRoutesManager.getInstance();
        if (keyCode == cfg.scanItemKey) {
            manager.scanItemsAtFeet();
        }
        if (keyCode == cfg.addStandingKey) {
            manager.addStanding();
        }
        if (keyCode == cfg.addAotvKey) {
            manager.addAotv();
        }
        if (keyCode == cfg.addPearlKey) {
            manager.addPearl();
        }
        if (keyCode == cfg.openKey) {
            Minecraft.getInstance().setScreenAndShow(
                    new sbs.modid.client.dungeons.secretroutes.ui.SecretRoutesScreen(null));
        }
    }
}
