/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.recipe.logic;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import sbs.modid.client.economy.prices.BazaarPriceCache;
import sbs.modid.client.economy.prices.ChatPriceCache;
import sbs.modid.client.economy.recipe.model.ItemRef;

import java.util.Locale;

/**
 * "Where do I buy this": {@code /bz <name>} for a Bazaar product, {@code /ahsearch <name>} for
 * everything else. Shared by the Recipe Viewer side panel (Shift + Click) and the Recipe Viewer
 * screen (Middle Click).
 */
public final class MarketSearch {

    private MarketSearch() {
    }

    /** Sends the search command for the item; false when there is no player or no usable name. */
    public static boolean run(ItemRef item) {
        Minecraft minecraft = Minecraft.getInstance();
        if (item == null || minecraft.player == null) {
            return false;
        }
        String name = searchName(item.name);
        if (name.isEmpty()) {
            return false;
        }
        String id = item.lookupId();
        boolean bazaar = BazaarPriceCache.getInstance().getBuy(id) != null
                || ChatPriceCache.getInstance().getUnitPrice(id) != null;
        minecraft.player.connection.sendCommand((bazaar ? "bz " : "ahsearch ") + name.toLowerCase(Locale.ROOT));
        return true;
    }

    /** Display name minus colour codes, a pet's "[Lvl n]" prefix and star glyphs. */
    static String searchName(String raw) {
        String s = raw == null ? null : ChatFormatting.stripFormatting(raw);
        if (s == null) {
            return "";
        }
        s = s.replaceFirst("^\\[Lvl \\d+\\]\\s*", "");
        s = s.replaceAll("[✪➊➋➌➍➎⚚]", "");
        return s.trim().replaceAll("\\s+", " ");
    }
}
