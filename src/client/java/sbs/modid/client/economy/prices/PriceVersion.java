/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.prices;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * One counter that moves whenever the data behind a price tooltip line is published: an LBIN or
 * Bazaar snapshot, a chat/menu-learned price, and the catalogues those lines name (enchant pools,
 * max levels, the item catalogue, the bits shop offers).
 *
 * <p>Writers call {@link #bump} from whatever thread published; readers only compare
 * {@link #get} for change - see {@link sbs.modid.client.core.item.TooltipLines}.
 */
public final class PriceVersion {

    private static final AtomicInteger VERSION = new AtomicInteger();

    private PriceVersion() {
    }

    /** Compare for change, never for order. */
    public static int get() {
        return VERSION.get();
    }

    /** Marks every cached tooltip line built from the previous data as stale. */
    public static void bump() {
        VERSION.incrementAndGet();
    }
}
