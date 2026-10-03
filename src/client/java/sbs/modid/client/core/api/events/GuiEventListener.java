/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.api.events;

/**
 * Listener for GUI lifecycle events. All methods are {@code default} no-ops, so a
 * future feature only needs to override the events it cares about.
 *
 * <p>Note: this is the mod's own listener type and is unrelated to Minecraft's
 * {@code net.minecraft.client.gui.components.events.GuiEventListener}.
 *
 * <p>Listeners are invoked on the client thread (during the tick that detected the
 * change), so implementations must be quick and must not block.
 */
public interface GuiEventListener {

    default void onGuiOpened(GuiOpenedEvent event) {
    }

    default void onGuiClosed(GuiClosedEvent event) {
    }

    default void onGuiChanged(GuiChangedEvent event) {
    }
}
