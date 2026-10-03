/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.api.events;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tiny event bus for GUI lifecycle events.
 *
 * <p>{@link GuiStateManager} posts events here; future systems register listeners.
 * Uses a {@link CopyOnWriteArrayList} so registration/iteration is thread-safe even
 * though posting currently happens only on the client thread.
 */
public final class GuiEvents {

    private static final List<GuiEventListener> LISTENERS = new CopyOnWriteArrayList<>();

    private GuiEvents() {
    }

    public static void register(GuiEventListener listener) {
        LISTENERS.add(listener);
    }

    public static void unregister(GuiEventListener listener) {
        LISTENERS.remove(listener);
    }

    public static void post(GuiOpenedEvent event) {
        for (GuiEventListener listener : LISTENERS) {
            listener.onGuiOpened(event);
        }
    }

    public static void post(GuiClosedEvent event) {
        for (GuiEventListener listener : LISTENERS) {
            listener.onGuiClosed(event);
        }
    }

    public static void post(GuiChangedEvent event) {
        for (GuiEventListener listener : LISTENERS) {
            listener.onGuiChanged(event);
        }
    }
}
