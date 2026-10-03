/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.quest.logic;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.helper.quest.model.Quest;

/**
 * The Quest Guide module: guided quest objectives.
 *
 * <p><b>Skeleton.</b> The feature set is still being defined, so this currently holds only the
 * master toggle and the place the rest will hang off. It is registered as a real module
 * ({@code ModuleManager.QUEST_GUIDE_ID}) with its own settings page and config block, so filling it
 * in later is additive – no wiring has to be invented at that point.
 *
 * <p>The pathfinding module ({@code sbs.modid.client.pathfinding}) is the intended companion: it
 * already resolves and draws a route to a waypoint, so a quest objective should become a waypoint
 * rather than growing a second, parallel routing system.
 */
public final class QuestGuide {

    private QuestGuide() {
    }

    /** Whether the module is switched on. Every future entry point should check this first. */
    public static boolean enabled() {
        return ConfigManager.getInstance().get().questGuide.enabled;
    }
}
