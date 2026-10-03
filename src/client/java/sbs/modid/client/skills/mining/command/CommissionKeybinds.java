/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.command;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.skills.mining.logic.CommissionRoute;

/**
 * The Commission Route hotkey, dispatched from {@code CommandKeyMixin} on a fresh key press while
 * in-world with no screen open: pin the route to the next commission, and unpin past the last one.
 *
 * <p>Not island-gated. The route only exists on the mining islands anyway, and a key that silently
 * does nothing where the player expects an answer is worse than one that tells them there is no
 * commission running - which is what {@link CommissionRoute#cycle()} does.
 */
public final class CommissionKeybinds {

    private CommissionKeybinds() {
    }

    public static void onKeyPressed(int keyCode) {
        int key = ConfigManager.getInstance().get().miningHelpers.commissionRouteKey;
        if (key != 0 && keyCode == key) {
            CommissionRoute.getInstance().cycle();
        }
    }
}
