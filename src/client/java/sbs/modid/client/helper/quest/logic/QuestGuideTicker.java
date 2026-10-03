/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.quest.logic;

import net.minecraft.client.Minecraft;
import sbs.modid.client.core.config.ConfigManager;

/**
 * The Quest Guide's per-tick housekeeping: resuming a quest after a restart, and keeping its
 * waypoint pointed at the current step.
 *
 * <p>Both are cheap and idempotent, but neither belongs in the tracker (which is the model) nor in
 * the overlay (which must not have side effects while rendering).
 *
 * <p>The resume needs no throttle: definitions are bundled, so re-attaching one is a lookup in a
 * list already in memory. An id that names no bundled quest simply leaves the tracker empty, and
 * retrying that costs nothing.
 */
public final class QuestGuideTicker {

    private QuestGuideTicker() {
    }

    /** Called once per client tick. */
    public static void tick(Minecraft minecraft) {
        if (!ConfigManager.getInstance().get().questGuide.enabled || minecraft.player == null) {
            return;
        }
        QuestTracker tracker = QuestTracker.getInstance();
        if (tracker.quest() == null) {
            tracker.resumeIfActive();
            return;
        }
        QuestWaypoints.sync();
    }
}
