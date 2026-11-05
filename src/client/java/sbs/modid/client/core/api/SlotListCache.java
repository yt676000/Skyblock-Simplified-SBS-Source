/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.api;

import java.util.List;

/**
 * The slot list of the last captured container, keyed on the screen instance, the menu's
 * {@code containerId} and its {@code stateId}. The server bumps the state id with every slot
 * change it sends, so an unchanged key means the slot list (names, lore, counts) is unchanged and
 * {@link GuiStateManager} reuses it instead of re-reading every slot each tick.
 *
 * <p>Client thread only. The cached list is immutable, so publishing it again is safe.
 */
final class SlotListCache {

    private Object screen;
    private int containerId;
    private int stateId;
    private List<GuiState.SlotInfo> slots;

    /** The cached slots when the key is unchanged, otherwise {@code null} (rebuild, then {@link #store}). */
    List<GuiState.SlotInfo> get(Object screen, int containerId, int stateId) {
        if (slots != null && this.screen == screen && this.containerId == containerId
                && this.stateId == stateId) {
            return slots;
        }
        return null;
    }

    void store(Object screen, int containerId, int stateId, List<GuiState.SlotInfo> slots) {
        this.screen = screen;
        this.containerId = containerId;
        this.stateId = stateId;
        this.slots = slots;
    }

    /** Drops the cached list (no container open), so a closed screen is not kept reachable. */
    void clear() {
        screen = null;
        slots = null;
    }
}
