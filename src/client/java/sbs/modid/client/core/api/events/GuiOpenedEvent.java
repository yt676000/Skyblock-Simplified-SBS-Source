/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.api.events;

/**
 * Fired once when any GUI / screen becomes open (transition from "no screen" to a
 * screen). Carries the new screen's simple class name and its (plain-text) title.
 *
 * <p>Future features (automation, tutorials, overlays) can react to this via
 * {@link GuiEvents#register(GuiEventListener)}.
 */
public record GuiOpenedEvent(String screenClass, String screenTitle) {
}
