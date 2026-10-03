/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.build.logic.SelectionManager;
import sbs.modid.client.helper.build.model.SelectionUx;

/**
 * Ending a selection and remembering when the player last worked on one.
 *
 * <p>Clearing is local state only - nothing is sent. The feedback goes to the action bar, where it
 * reads at a glance without filling chat.
 */
public final class SelectionActions {

    private static volatile long lastUse;

    private SelectionActions() {
    }

    /** A build key or command was used now: the selection stays drawn for a while without the stick. */
    public static void touch() {
        lastUse = System.currentTimeMillis();
    }

    public static long lastUse() {
        return lastUse;
    }

    /** Whether the selection is drawn right now (see {@link SelectionUx#visible}). */
    public static boolean visible() {
        return SelectionUx.visible(BuildToolsOwner.cfg().hideSelectionUnlessHeld, MagicStickInput.holding(),
                lastUse, System.currentTimeMillis());
    }

    /** Clears the selection and the live preview, with a short action-bar line. */
    public static void clear() {
        SelectionManager.getInstance().clear();
        MagicStickInput.endLivePreview();
        touch();
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.sendOverlayMessage(Component.literal("Selection cleared").withColor(0xFFE24B));
        }
    }
}
