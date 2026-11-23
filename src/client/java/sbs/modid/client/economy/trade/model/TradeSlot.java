/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.trade.model;

import java.util.List;

/**
 * One non-empty slot of the trade menu as plain text, read on the client thread.
 *
 * @param material vanilla item path ({@code "gray_stained_glass_pane"})
 * @param name     display name, plain, no formatting codes
 * @param lore     lore lines, plain
 * @param print    the item's fingerprint; {@code null} for buttons and dividers
 * @param value    appraised coin value of the whole stack, {@code null} when no price is known
 */
public record TradeSlot(int slot, String material, String name, List<String> lore, int count,
                        ItemPrint print, Long value) {

    public TradeSlot {
        material = material == null ? "" : material;
        name = name == null ? "" : name;
        lore = lore == null ? List.of() : List.copyOf(lore);
    }

    public static TradeSlot button(int slot, String material, String name, int count, String... lore) {
        return new TradeSlot(slot, material, name, List.of(lore), count, null, null);
    }
}
