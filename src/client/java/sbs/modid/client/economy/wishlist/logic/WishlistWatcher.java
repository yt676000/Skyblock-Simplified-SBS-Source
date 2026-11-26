/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.wishlist.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.alert.AlertChannel;
import sbs.modid.client.core.alert.AlertChannels;
import sbs.modid.client.core.alert.Alerts;
import sbs.modid.client.core.audio.SbsAudio;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.core.net.validation.ValidationException;
import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.economy.wishlist.model.AuctionItem;
import sbs.modid.client.economy.wishlist.model.WishlistEntry;
import sbs.modid.client.social.chat.logic.SBSChat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Watches the live auction crawl for wishlist matches and tells the player.
 *
 * <p><b>No request of its own.</b> {@code LbinCache} already walks every auction page once a minute
 * for the lowest-BIN tooltip; this class is handed each BIN auction of that walk. An auction's
 * {@code item_bytes} is decoded only when its name key is on the wishlist and its price is within
 * the entry's limit, so a list of five items costs five name lookups per auction and a handful of
 * NBT decodes per crawl, not seventy thousand.
 *
 * <p>Runs on the crawl thread. Alerts are handed to the game thread by {@link Alerts} and by
 * {@link Minecraft#execute}. Each auction alerts once per session; editing the list forgets that,
 * so a changed entry is checked afresh against what is already listed.
 *
 * <p>This only tells the player; buying is theirs to do. The chat line runs {@code /viewauction},
 * which opens the auction's own menu and buys nothing.
 */
public final class WishlistWatcher {

    private static final WishlistWatcher INSTANCE = new WishlistWatcher();

    /** At most this many alerts per crawl; the rest are summed up in one line. */
    private static final int MAX_ALERTS_PER_CRAWL = 5;

    /** Auctions already alerted, bounded so a long session cannot grow it without limit. */
    private static final int MAX_SEEN = 10_000;

    private final Set<String> seen = new LinkedHashSet<>();

    /** Name key to the enabled entries for it, taken from the config when a crawl begins. */
    private Map<String, List<WishlistEntry>> byName = Map.of();
    private int alertsThisCrawl;
    private int suppressedThisCrawl;

    private WishlistWatcher() {
    }

    public static WishlistWatcher getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.AhWishlistSettings cfg() {
        return ConfigManager.getInstance().get().ahWishlist;
    }

    /** Whether the crawl should run for the wishlist's sake: on, and at least one enabled entry. */
    public static boolean wantsCrawl() {
        SBSConfig.AhWishlistSettings cfg = cfg();
        if (!cfg.enabled) {
            return false;
        }
        for (WishlistEntry entry : cfg.entries) {
            if (entry.enabled && !SkyblockItem.normalizeName(entry.itemName).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** The list changed: alert again for auctions that were already reported. */
    public synchronized void entriesChanged() {
        seen.clear();
    }

    /** Called by the crawl before its first page. */
    public synchronized void beginCrawl() {
        alertsThisCrawl = 0;
        suppressedThisCrawl = 0;
        Map<String, List<WishlistEntry>> next = new HashMap<>();
        if (cfg().enabled) {
            for (WishlistEntry entry : cfg().entries) {
                String key = SkyblockItem.normalizeName(entry.itemName);
                if (entry.enabled && !key.isEmpty()) {
                    next.computeIfAbsent(key, unused -> new ArrayList<>()).add(entry);
                }
            }
        }
        byName = next;
    }

    /** Called by the crawl after its last page. */
    public synchronized void endCrawl() {
        if (suppressedThisCrawl > 0) {
            int more = suppressedThisCrawl;
            Minecraft.getInstance().execute(() -> SBSChat.send(Component.literal(
                    "AH Wishlist: " + more + " more match" + (more == 1 ? "" : "es")
                            + " this scan - search the Auction House for them.").withColor(SBSChat.WHITE)));
        }
        byName = Map.of();
    }

    /**
     * Offers one BIN auction of the crawl.
     *
     * @param nameKey   the auction's normalised item name
     * @param auctionId validated 32-hex auction id
     * @param itemName  the auction's own item name, for the alert text
     * @param price     the BIN price
     * @param itemBytes the auction's {@code item_bytes}
     */
    public synchronized void offer(String nameKey, String auctionId, String itemName, long price,
                                   String itemBytes) {
        List<WishlistEntry> entries = byName.get(nameKey);
        if (entries == null || auctionId == null || seen.contains(auctionId)) {
            return;
        }
        boolean affordable = false;
        for (WishlistEntry entry : entries) {
            affordable |= entry.maxPrice <= 0 || price <= entry.maxPrice;
        }
        if (!affordable || itemBytes == null) {
            return;
        }
        AuctionItem item;
        try {
            item = AuctionItemReader.fromItemBytes(itemBytes);
        } catch (ValidationException e) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Wishlist] Unreadable item data on auction {}: {}",
                    auctionId, e.getMessage());
            return;
        }
        if (item == null) {
            return;
        }
        for (WishlistEntry entry : entries) {
            if (WishlistMatcher.match(entry, item, price).matched()) {
                remember(auctionId);
                alert(entry, auctionId, itemName, price);
                return;
            }
        }
    }

    private void remember(String auctionId) {
        if (seen.size() >= MAX_SEEN) {
            var it = seen.iterator();
            it.next();
            it.remove();
        }
        seen.add(auctionId);
    }

    private void alert(WishlistEntry entry, String auctionId, String itemName, long price) {
        if (alertsThisCrawl >= MAX_ALERTS_PER_CRAWL) {
            suppressedThisCrawl++;
            return;
        }
        alertsThisCrawl++;
        String name = itemName == null || itemName.isBlank() ? entry.itemName : itemName;
        String coins = NumberDisplay.format(price);
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Wishlist] Match for '{}': auction {} at {}",
                entry.itemName, auctionId, price);
        int mask = cfg().alertChannels;
        // The chat line is sent here rather than through Alerts' chat channel: it carries the click
        // that opens the auction, which the generic channel cannot.
        if (AlertChannels.has(mask, AlertChannel.CHAT)) {
            Minecraft.getInstance().execute(() -> SBSChat.send(chatLine(name, coins, auctionId)));
        }
        Alerts.send(new Alerts.Alert("Wishlist: " + entry.itemName, name + " for " + coins,
                SbsAudio.Tone.CHIME, null), mask & ~AlertChannel.CHAT.bit());
    }

    private static Component chatLine(String name, String coins, String auctionId) {
        String command = "/viewauction " + auctionId;
        MutableComponent line = Component.literal("Wishlist match: ").withColor(SBSChat.WHITE)
                .append(Component.literal(name).withColor(0xFFC94D))
                .append(Component.literal(" for " + coins + "  ").withColor(SBSChat.WHITE))
                .append(Component.literal("[OPEN]").withColor(0x57D977).withStyle(style -> style
                        .withBold(true)
                        .withClickEvent(new ClickEvent.RunCommand(command))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("Opens the auction")))));
        return line;
    }
}
