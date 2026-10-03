/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.essenceshop.logic;

import net.minecraft.client.Minecraft;
import sbs.modid.client.economy.essenceshop.model.EssenceType;
import sbs.modid.client.economy.prices.BazaarPriceCache;

/**
 * The two things the overview does with the Bazaar: price the essence it is asking for, and open it.
 *
 * <p><b>One deliberate click, one command.</b> Nothing here runs from a hover, a scroll, a screen
 * opening or a recompute - the only caller is a click on the panel's own button, and a short
 * cooldown means a double click cannot become two commands. The player is not sent back to the shop
 * afterwards either: returning them would be a second command nobody asked for.
 */
public final class EssenceBazaar {

    /** Two commands cannot leave closer together than this, whatever the mouse does. */
    private static final long COOLDOWN_MS = 600L;

    private static long lastSentAt;

    private EssenceBazaar() {
    }

    /**
     * Opens Hypixel's Bazaar search for this essence.
     *
     * <p>The screen is closed first: the command opens a Hypixel menu, and sending it while a
     * container is still up leaves the client and the server disagreeing about what is open.
     *
     * @return whether a command was actually sent (false while the cooldown is running)
     */
    public static boolean open(EssenceType type) {
        Minecraft minecraft = Minecraft.getInstance();
        if (type == null || minecraft.player == null || minecraft.player.connection == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - lastSentAt < COOLDOWN_MS) {
            return false;
        }
        lastSentAt = now;
        minecraft.setScreenAndShow(null);
        minecraft.player.connection.sendCommand("bz " + type.bazaarQuery());
        return true;
    }

    /**
     * What this much essence costs in coins right now, or {@code null} when no price is cached.
     *
     * <p><b>Buy side</b> - the instant-buy price, which is what acquiring the essence actually
     * costs. The sell side would read lower and answer a question nobody asked. It is an estimate
     * either way: the price moves, and a large order moves it further.
     */
    public static Long coinsFor(EssenceType type, long essence) {
        if (type == null || essence <= 0) {
            return null;
        }
        Long unit = BazaarPriceCache.getInstance().getBuy(type.id());
        if (unit == null || unit <= 0) {
            return null;
        }
        return unit * essence;
    }
}
