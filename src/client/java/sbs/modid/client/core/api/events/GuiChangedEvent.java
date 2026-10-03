/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.api.events;

/**
 * Fired when an already-open GUI changes its type (screen class) or title without
 * fully closing first – e.g. navigating between SkyBlock menus. Carries both the
 * previous and the new screen class / title.
 */
public record GuiChangedEvent(String oldScreenClass, String oldScreenTitle,
                              String newScreenClass, String newScreenTitle) {
}
