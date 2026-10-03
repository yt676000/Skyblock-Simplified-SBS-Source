/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.api.events;

/**
 * Fired once when the currently open GUI / screen is closed (transition from a
 * screen back to "no screen"). Carries the class name and title of the screen that
 * was just closed.
 */
public record GuiClosedEvent(String screenClass, String screenTitle) {
}
