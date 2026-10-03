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
 * Immutable snapshot of the currently open Minecraft screen / container.
 *
 * <p>This is the <b>only</b> data structure shared between the Minecraft side
 * ({@link GuiStateManager}, which fills it on the client thread by reading the real
 * {@code AbstractContainerScreen} / {@code AbstractContainerMenu}) and the HTTP side
 * ({@code ApiServer}, which serializes it). It contains only plain types (no
 * Minecraft objects), keeping the two sides cleanly separated and the snapshot safe
 * to read from the HTTP thread.
 */
public record GuiState(
        boolean isOpen,
        String screenClass,
        String title,
        int rows,
        int slotCount,
        List<SlotInfo> slots,
        String player,
        boolean worldLoaded,
        int fps,
        long timestamp
) {

    /**
     * One slot of the open container.
     *
     * @param slot   slot index inside the menu (matches click indices / highlight ids)
     * @param x      slot X relative to the GUI's top-left corner ({@code Slot.x})
     * @param y      slot Y relative to the GUI's top-left corner ({@code Slot.y})
     * @param name   item display name, or {@code null} if empty
     * @param count  item stack size, or 0 if empty
     * @param itemId registry id (e.g. {@code minecraft:diamond}), or {@code null}
     * @param rarity item rarity name (e.g. {@code COMMON}), or {@code null}
     * @param lore   lore lines (may be empty)
     * @param empty  whether the slot holds no item
     */
    public record SlotInfo(int slot, int x, int y, String name, int count,
                           String itemId, String rarity, List<String> lore, boolean empty) {
    }

    /** A neutral "nothing open" snapshot. */
    public static GuiState empty() {
        return new GuiState(false, null, null, 0, 0, List.of(),
                null, false, 0, System.currentTimeMillis() / 1000L);
    }
}
