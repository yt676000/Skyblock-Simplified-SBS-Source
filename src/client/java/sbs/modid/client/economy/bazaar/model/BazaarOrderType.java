/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.bazaar.model;

/**
 * The two kinds of order a player can place on the Hypixel Bazaar.
 *
 * <p>A {@link #BUY} order competes against other buy orders (highest price wins); a
 * {@link #SELL} offer competes against other sell offers (lowest price wins). This drives
 * which Hypixel API summary list ({@code buy_summary} / {@code sell_summary}) a stored
 * order is compared against.
 */
public enum BazaarOrderType {
    BUY,
    SELL
}
