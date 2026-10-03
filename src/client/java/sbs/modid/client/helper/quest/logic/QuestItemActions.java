/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.quest.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import sbs.modid.client.economy.recipe.ui.RecipeViewerScreen;
import sbs.modid.client.economy.prices.BazaarPriceCache;
import sbs.modid.client.economy.prices.ChatPriceCache;
import sbs.modid.client.economy.recipe.model.ItemWikiLinks;
import sbs.modid.client.helper.quest.model.Quest;

import java.util.Locale;

/**
 * The ways to go get a required item: buy it, craft it, or read about it.
 *
 * <p>Each is a one-liner, but they live together here so the Quest Guide opens an item exactly the
 * way the Recipe Viewer already does – same command, same wiki host, same market choice – instead of
 * a second set of subtly different behaviours.
 */
public final class QuestItemActions {

    private QuestItemActions() {
    }

    /** Whether the item trades on the Bazaar (which decides {@code /bz} vs {@code /ahsearch}). */
    public static boolean onBazaar(String skyblockId) {
        return BazaarPriceCache.getInstance().getBuy(skyblockId) != null
                || ChatPriceCache.getInstance().getUnitPrice(skyblockId) != null;
    }

    /**
     * Opens the item's market: the Bazaar when it trades there, the auction house otherwise.
     * Closes the screen first – both commands open a Hypixel menu, and sending one while our own
     * screen is up leaves the client and server disagreeing about what is open.
     */
    public static void openMarket(String skyblockId, String displayName) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || displayName == null) {
            return;
        }
        boolean bazaar = onBazaar(skyblockId);
        String query = displayName.trim().toLowerCase(Locale.ROOT);
        minecraft.setScreenAndShow(null);
        minecraft.player.connection.sendCommand((bazaar ? "bz " : "ahsearch ") + query);
    }

    /** Opens the item in the Recipe Viewer, searched by name. */
    public static void openRecipe(String displayName) {
        Minecraft.getInstance().setScreenAndShow(
                new RecipeViewerScreen(displayName == null ? "" : displayName));
    }

    /** Opens the item's wiki page, through the vanilla link confirmation. */
    public static void openWiki(Screen parent, String displayName) {
        if (displayName == null) {
            return;
        }
        ConfirmLinkScreen.confirmLinkNow(parent, ItemWikiLinks.officialUrl(displayName));
    }

    /** The label for the market button, so the UI says where the click actually goes. */
    public static String marketLabel(String skyblockId) {
        return onBazaar(skyblockId) ? "Bazaar" : "Auction";
    }
}
