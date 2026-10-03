/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.bazaar.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.economy.bazaar.model.BazaarOrder;
import sbs.modid.client.economy.bazaar.model.BazaarOrderType;
import sbs.modid.client.economy.bazaar.model.BazaarStatus;
import sbs.modid.client.economy.prices.ChatPriceCache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads and caches the player's Bazaar orders from the "Your Bazaar Orders" and
 * "Co-op Bazaar Orders" menus, and keeps them current in between from the Bazaar chat lines
 * (order setup, filled, claimed, cancelled) so the menu never has to be reopened just to
 * refresh the tracker.
 *
 * <p>Ticked once per client tick (from {@code GuiTrackingMixin}). Whenever one of those
 * menus is open it re-scans every order slot, so the cache always reflects the latest
 * orders – it is rebuilt from scratch on each open and stays current as the server fills
 * the slots in. Live {@link BazaarStatus} values computed by {@link BazaarSyncService} are
 * carried across re-scans by order key, and the cache is persisted to {@link SBSConfig} so
 * the background sync keeps tracking the same orders after a restart.
 *
 * <p>Reads reuse the same {@code AbstractContainerScreen}/{@code ChestMenu} access used by
 * {@link sbs.modid.client.core.api.GuiStateManager}; the Hypixel SkyBlock id comes from the
 * item's {@code ExtraAttributes.id} custom data so stored orders line up with the Bazaar
 * API keys.
 */
public final class BazaarOrderTracker implements sbs.modid.client.core.config.ProfileScopedStore {

    private static final BazaarOrderTracker INSTANCE = new BazaarOrderTracker();

    /** Matches the first number (with optional thousands separators / decimals) in a line. */
    private static final Pattern NUMBER = Pattern.compile("([0-9][0-9,]*(?:\\.[0-9]+)?)");

    /** The Minecraft formatting prefix char (built from its code point to stay encoding-safe). */
    private static final String SECTION_SIGN = String.valueOf((char) 0x00A7);

    /** Immutable snapshot, safe to read from the render / API threads. */
    private volatile List<BazaarOrder> orders = List.of();

    private boolean loaded;

    // Debug bookkeeping (so logging happens once per open / change, never every tick).
    private Object lastLoggedScreen;
    private int lastLoggedCount = -1;

    private BazaarOrderTracker() {
        sbs.modid.client.core.config.ProfileContext.getInstance().register(this);
    }

    public static BazaarOrderTracker getInstance() {
        return INSTANCE;
    }

    /** Current cached orders (immutable snapshot). */
    public List<BazaarOrder> getOrders() {
        return orders;
    }

    /** Loads the persisted orders once, from the current account+profile file. */
    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        orders = readOrdersFile();
    }

    private static List<BazaarOrder> readOrdersFile() {
        java.nio.file.Path path = sbs.modid.client.core.config.SBSFiles.profileFile("bazaar_orders.json");
        try {
            if (!java.nio.file.Files.exists(path)) {
                return List.of();
            }
            java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<List<BazaarOrder>>() {
            }.getType();
            try (var reader = java.nio.file.Files.newBufferedReader(path)) {
                List<BazaarOrder> list = sbs.modid.client.core.config.SBSFiles.GSON.fromJson(reader, type);
                if (list == null) {
                    return List.of();
                }
                // The competitive status is a verdict about a market that kept moving while the game
                // was closed, but it rides along in the JSON (only lastNotifiedStatus is transient).
                // Restoring it verbatim showed a days-old "Best Offer" as current until the first
                // successful compare - and permanently for an order whose id the API never resolves.
                // FILLED is kept: that one is a fact about the order, not about the competition.
                for (BazaarOrder order : list) {
                    if (order == null) {
                        continue;
                    }
                    if (order.status() != BazaarStatus.FILLED) {
                        order.setStatus(BazaarStatus.UNKNOWN);
                    }
                    // Gson writes fields directly, so the constructor's tick snap is bypassed here:
                    // re-apply it, otherwise an off-grid price written by an older build survives.
                    order.snapPriceToTick();
                }
                return List.copyOf(list);
            }
        } catch (Throwable t) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS] Failed to load cached Bazaar orders", t);
            return List.of();
        }
    }

    /** Save the current orders to the profile file immediately (on a profile switch). */
    @Override
    public void flushProfile() {
        persist(orders);
    }

    /** Drop the cached orders and reload them from the (now current) profile's file. */
    @Override
    public void reloadProfile() {
        loaded = false;
        orders = List.of();
        ensureLoaded();
    }

    /** Re-scans the orders menu (if open) once per client tick. */
    public void tick(Minecraft minecraft) {
        try {
            ensureLoaded();
            if (minecraft == null) {
                return;
            }
            Screen screen = sbs.modid.client.core.api.GuiStateManager.getInstance().getCurrentScreen();
            if (!(screen instanceof AbstractContainerScreen<?> container)) {
                lastLoggedScreen = null; // reset so the next container open logs again
                return;
            }
            String title = screen.getTitle() != null ? screen.getTitle().getString() : "";

            // Any Bazaar / Auction House GUI being open marks the search context, so a sign that opens
            // right after is recognised as its search input (see BazaarSearchHistory).
            if (isBazaarGui(title)) {
                BazaarSearchHistory.getInstance().noteSearchContext(BazaarSearchHistory.Source.BAZAAR);
            } else if (BazaarSearchHistory.isAuctionGui(title)) {
                BazaarSearchHistory.getInstance().noteSearchContext(BazaarSearchHistory.Source.AUCTION);
            }

            // Step 1/2 debug: log the real inventory title once per open so it is obvious
            // whether the title is read and whether it matches a Bazaar orders menu.
            if (screen != lastLoggedScreen) {
                lastLoggedScreen = screen;
                lastLoggedCount = -1;
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Bazaar] Container opened: title='{}' -> ordersMenu={}",
                        title, isOrdersMenu(title));
            }
            // The confirmation menu is the only place Hypixel states the exact unit price, so read it
            // while it is up - the chat line that follows only carries a rounded total.
            if (isConfirmMenu(title)) {
                capturePendingConfirm(container.getMenu());
                return;
            }
            // The single-order detail screen: remember which order is being looked at, so the refund
            // line that may follow can be attributed to it rather than guessed at.
            if (isOrderDetailMenu(title)) {
                captureOpenDetail(container.getMenu());
                return;
            }
            if (!isOrdersMenu(title)) {
                return;
            }

            AbstractContainerMenu menu = container.getMenu();
            int upper = scanLimit(menu);

            // Step 3-8 debug: scan every container slot and collect parsed orders.
            List<BazaarOrder> scanned = new ArrayList<>();
            for (int i = 0; i < upper; i++) {
                ItemStack stack = menu.getSlot(i).getItem();
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                BazaarOrder order = parseOrder(stack, i);
                if (order != null) {
                    scanned.add(order);
                }
            }

            if (scanned.size() != lastLoggedCount) {
                lastLoggedCount = scanned.size();
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Bazaar] Scanned {} order(s) from {} slot(s).",
                        scanned.size(), upper);
                for (BazaarOrder order : scanned) {
                    SkyblockSimplifiedSBS.LOGGER.info("[SBS][Bazaar]   - {} {} x{} @ {} (id={}, slot={})",
                            order.type(), order.itemName(), order.amount(), order.price(), order.itemId(), order.slot());
                }
            }

            applyScan(scanned);
        } catch (Throwable t) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][Bazaar] Order scan failed", t);
        }
    }

    /** Number of (non-player-inventory) slots to scan in the open container. */
    private static int scanLimit(AbstractContainerMenu menu) {
        int total = menu.getItems().size();
        if (menu instanceof ChestMenu chest) {
            return Math.min(chest.getRowCount() * 9, total);
        }
        // Unknown container type: scan everything except the 36 player-inventory slots.
        return Math.max(0, total - 36);
    }

    /**
     * True for the "Bazaar Orders" and "Co-op Bazaar Orders" inventory titles (matched on
     * the real screen title – never on translated or external logging text).
     */
    public static boolean isOrdersMenu(String title) {
        if (title == null) {
            return false;
        }
        String t = title.trim().toLowerCase(Locale.ROOT);
        return t.equals("bazaar orders") || t.endsWith("bazaar orders");
    }

    /**
     * True for EVERY Bazaar GUI, matched on the real container titles Hypixel uses:
     * the category/search menus ("Bazaar ➜ ..."), the orders menus ("... Bazaar Orders"),
     * the product pages ("➜ Enchanted Diamond") and the order/instant confirm flows.
     */
    public static boolean isBazaarGui(String title) {
        if (title == null) {
            return false;
        }
        String t = title.trim().toLowerCase(Locale.ROOT);
        return t.contains("bazaar")
                || t.startsWith("➜")               // "➜ <product>" pages
                || t.startsWith("confirm instant")       // Confirm Instant Buy / Sell
                || t.equals("confirm buy order")
                || t.equals("confirm sell offer")
                || t.equals("order options");
    }

    // ------------------------------------------------------------------
    // Cache update (carry over live status, persist only on change)
    // ------------------------------------------------------------------

    private void applyScan(List<BazaarOrder> scanned) {
        // Feed every scraped order's unit price into the shared local price cache – the exact same
        // on-disk store the chat listener writes to – so tooltips see menu- and chat-sourced prices
        // interchangeably without needing the menu reopened.
        for (BazaarOrder order : scanned) {
            if (order.price() > 0) {
                ChatPriceCache.getInstance().putUnitPrice(order.itemId(), Math.round(order.price()));
            }
        }

        List<BazaarOrder> current = orders;

        // Carry the live status of still-present orders over to the freshly scanned ones.
        Map<String, BazaarOrder> previous = new HashMap<>();
        for (BazaarOrder order : current) {
            previous.put(order.key(), order);
        }
        for (BazaarOrder order : scanned) {
            BazaarOrder prev = previous.get(order.key());
            // Keep a freshly-detected FILLED; only inherit the previous competitive status when the
            // fresh scan has none of its own yet.
            if (prev != null && order.status() == BazaarStatus.UNKNOWN) {
                // ...but never inherit FILLED. This scan just read the real slot lore and found the
                // order NOT filled, so that reading wins. Inheriting it made a FILLED sticky forever:
                // BazaarSyncService skips filled orders outright, so one bad detection (a lore false
                // positive, or markFilledFromChat picking the wrong one of two same-item offers -
                // it matches on name+side only, not price) froze that order's status permanently.
                if (prev.status() != BazaarStatus.FILLED) {
                    order.setStatus(prev.status());
                }
                order.setLastNotifiedStatus(prev.lastNotifiedStatus());
            }
        }

        // Nothing changed (same orders in the same slots): keep the existing objects so the
        // API thread's in-place status updates are preserved, and avoid a needless disk write.
        if (sameOrders(current, scanned)) {
            // ...but the fill DOES move while the menu sits open, and sameOrders cannot see it:
            // it compares slot and key, and key() is item|side|price. Carry the fresh figure over
            // in place, or an order watched as it fills keeps reporting the fill it had on open.
            // Deliberately without persisting - this changes many times a minute, it is re-read
            // from the slots on the next scan, and the refund is what a cancellation actually
            // measures the remainder from.
            for (int i = 0; i < current.size(); i++) {
                current.get(i).setFilled(scanned.get(i).filled());
            }
            return;
        }
        orders = List.copyOf(scanned);
        persist(scanned);
    }

    private static boolean sameOrders(List<BazaarOrder> a, List<BazaarOrder> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            BazaarOrder x = a.get(i);
            BazaarOrder y = b.get(i);
            if (x.slot() != y.slot() || !x.key().equals(y.key())) {
                return false;
            }
        }
        return true;
    }

    private void persist(List<BazaarOrder> snapshot) {
        try {
            java.nio.file.Path path = sbs.modid.client.core.config.SBSFiles.profileFile("bazaar_orders.json");
            sbs.modid.client.core.config.SBSFiles.ensureParent(path);
            try (var writer = java.nio.file.Files.newBufferedWriter(path)) {
                sbs.modid.client.core.config.SBSFiles.GSON.toJson(snapshot, writer);
            }
        } catch (Throwable t) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS] Failed to persist Bazaar orders", t);
        }
    }

    // ------------------------------------------------------------------
    // Chat-driven updates (add / fill / remove without the menu open)
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Exact price capture from the confirmation menu
    // ------------------------------------------------------------------

    /**
     * The exact figures read off the confirmation menu, waiting for the chat line that says the order
     * actually went through.
     *
     * <p>The menu is not proof of anything on its own - you can open it and walk away, or the order
     * can fail - so it is not turned into an order here. The chat "Setup!" line stays the proof that
     * one exists; this only supplies the price that line is too coarse to carry.
     */
    private record PendingConfirm(BazaarOrderType type, String itemName, int amount,
                                  double unitPrice, long capturedAt) {
    }

    /** How long a captured confirmation stays valid; the chat line follows within a tick or two. */
    private static final long CONFIRM_TTL_MS = 15_000L;

    /** "Price per unit: 13,615,745.5 coins" - the whole point of reading this menu. */
    private static final Pattern CONFIRM_UNIT_PRICE =
            Pattern.compile("(?i)price\\s+per\\s+unit\\s*:\\s*([\\d,.]+)");
    /** "Order: 1x First Master Star" on a buy, "Selling: 1x Cobblestone" on a sell. */
    private static final Pattern CONFIRM_AMOUNT =
            Pattern.compile("(?i)^(?:order|selling|buying)\\s*:\\s*([\\d,]+)x\\s+(.+?)\\s*$");

    private volatile PendingConfirm pendingConfirm;

    /** "Confirm Buy Order" / "Confirm Sell Offer". */
    static boolean isConfirmMenu(String title) {
        String lower = stripCodes(title == null ? "" : title).toLowerCase(Locale.ROOT);
        return lower.contains("confirm") && (lower.contains("order") || lower.contains("offer"));
    }

    /**
     * Reads the submit button's tooltip: it states the unit price to the tenth of a coin, which is
     * the number the chat line rounds away.
     */
    private void capturePendingConfirm(AbstractContainerMenu menu) {
        if (menu == null) {
            return;
        }
        int upper = scanLimit(menu);
        for (int i = 0; i < upper; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            List<String> lore = extractLore(stack);
            boolean submit = false;
            for (String line : lore) {
                if (line.toLowerCase(Locale.ROOT).contains("click to submit order")) {
                    submit = true;
                    break;
                }
            }
            if (!submit) {
                continue;
            }
            String name = stripCodes(stack.getHoverName().getString());
            BazaarOrderType type = detectType(name, lore);
            double unit = -1;
            int amount = -1;
            String itemName = null;
            for (String line : lore) {
                String text = line.trim();
                Matcher price = CONFIRM_UNIT_PRICE.matcher(text);
                if (price.find()) {
                    unit = parseNumber(price.group(1));
                    continue;
                }
                Matcher order = CONFIRM_AMOUNT.matcher(text);
                if (order.matches()) {
                    amount = parseInt(order.group(1));
                    itemName = order.group(2).trim();
                }
            }
            if (type != null && unit >= 0 && amount > 0 && itemName != null) {
                PendingConfirm previous = pendingConfirm;
                pendingConfirm = new PendingConfirm(type, itemName, amount, unit,
                        System.currentTimeMillis());
                if (previous == null || previous.unitPrice() != unit
                        || !itemName.equals(previous.itemName()) || previous.amount() != amount) {
                    SkyblockSimplifiedSBS.LOGGER.info(
                            "[SBS][Bazaar] Confirm menu: {} {}x {} @ {} per unit",
                            type, amount, itemName, unit);
                }
            }
            return;
        }
    }

    /**
     * The exact unit price for an order the chat just announced, or {@code -1} when the confirmation
     * menu was never seen for it (an order placed from a co-op member, say).
     */
    private double exactUnitPrice(BazaarOrderType type, int amount, String itemName) {
        PendingConfirm confirm = pendingConfirm;
        if (confirm == null || confirm.type() != type || confirm.amount() != amount
                || !confirm.itemName().equalsIgnoreCase(itemName.trim())
                || System.currentTimeMillis() - confirm.capturedAt() > CONFIRM_TTL_MS) {
            return -1;
        }
        pendingConfirm = null;   // consumed - a second Setup line must not reuse this price
        return confirm.unitPrice();
    }

    // ------------------------------------------------------------------
    // The single-order detail screen ("Order Options")
    // ------------------------------------------------------------------

    /**
     * The order the player currently has open in the detail screen, with the amounts as that screen
     * last stated them.
     *
     * <p>Its job is <b>identification, not measurement</b>. When the refund line arrives it says
     * which of several same-item orders was cancelled - the thing coins alone cannot tell you - while
     * the remainder itself is still computed from the refund, which is the figure Hypixel settled on
     * after any fill that landed between opening this screen and confirming the cancel.
     */
    private record OpenDetail(String itemId, String itemName, BazaarOrderType type, double price,
                              int amount, int filled, long seenAt) {

        boolean live() {
            return System.currentTimeMillis() - seenAt < DETAIL_TTL_MS;
        }
    }

    /** How long a detail-screen snapshot stays usable after the screen was last seen. */
    private static final long DETAIL_TTL_MS = 20_000L;

    private volatile OpenDetail openDetail;

    /**
     * True for the single-order detail screen.
     *
     * <p>Matched on the title Hypixel uses for it, and then confirmed against the screen's contents
     * in {@link #captureOpenDetail} - a title alone would claim any menu that happened to share the
     * words, and slot indices would break the first time the layout shifts by one. The signature is
     * the pair the screen is defined by: an order slot with a price-per-unit line, and a slot whose
     * lore offers to cancel it.
     */
    public static boolean isOrderDetailMenu(String title) {
        if (title == null) {
            return false;
        }
        String t = stripCodes(title).trim().toLowerCase(Locale.ROOT);
        return t.equals("order options") || t.startsWith("order options");
    }

    /** Lore of the slot that cancels the order - the second half of the detail screen's signature. */
    private static boolean isCancelSlot(String name, List<String> lore) {
        for (String line : iterate(name, lore)) {
            String l = line.toLowerCase(Locale.ROOT);
            if (l.contains("cancel order") || l.contains("cancel offer")
                    || (l.contains("cancel") && l.contains("refund"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads the open detail screen: the order it is showing, and whether it really offers to cancel.
     *
     * <p>Nothing is recorded here. A player who opens this screen and walks away has not cancelled
     * anything, and writing a history row on the strength of a screen being open would fill the panel
     * with orders that are still live.
     */
    private void captureOpenDetail(AbstractContainerMenu menu) {
        if (menu == null) {
            return;
        }
        int upper = scanLimit(menu);
        BazaarOrder subject = null;
        boolean cancellable = false;
        for (int i = 0; i < upper; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String name = stripCodes(stack.getHoverName().getString());
            List<String> lore = extractLore(stack);
            if (!cancellable && isCancelSlot(name, lore)) {
                cancellable = true;
            }
            if (subject == null) {
                BazaarOrder parsed = parseOrder(stack, i);
                if (parsed != null) {
                    subject = parsed;
                }
            }
        }
        if (subject == null || !cancellable) {
            return; // title matched but the layout did not - not the screen we mean
        }
        openDetail = new OpenDetail(subject.itemId(), subject.itemName(), subject.type(),
                subject.price(), subject.amount(), subject.filled(), System.currentTimeMillis());
    }

    /** "[Bazaar] Buy Order Setup! 64x Cobblestone for 70.4 coins." (total coins, not per unit). */
    private static final Pattern CHAT_SETUP = Pattern.compile(
            "(?i)\\[Bazaar\\]\\s+(Buy Order|Sell Offer)\\s+Setup!\\s+([\\d,]+)x\\s+(.+?)\\s+for\\s+([\\d,.]+)\\s+coins");

    /** "[Bazaar] Your Sell Offer for 1x Second Master Star was filled!". */
    private static final Pattern CHAT_FILLED = Pattern.compile(
            "(?i)\\[Bazaar\\]\\s+Your\\s+(Buy Order|Sell Offer)\\s+for\\s+([\\d,]+)x\\s+(.+?)\\s+was\\s+filled");

    /** "[Bazaar] Claimed 16,425,732 coins from selling 1x Second Master Star at 16,591,649 each!". */
    private static final Pattern CHAT_CLAIM_SELL = Pattern.compile(
            "(?i)\\[Bazaar\\]\\s+Claimed\\s+[\\d,.]+\\s+coins?\\s+from\\s+selling\\s+([\\d,]+)x\\s+(.+?)\\s+at\\s+([\\d,.]+)\\s+each");

    /** "[Bazaar] Claimed 64x Cobblestone worth 70.4 coins bought for 1.1 each!" (wording tolerant). */
    private static final Pattern CHAT_CLAIM_BUY = Pattern.compile(
            "(?i)\\[Bazaar\\]\\s+Claimed\\s+([\\d,]+)x\\s+(.+?)\\s+(?:worth|bought)\\b.*?([\\d,.]+)\\s+each");

    /** "[Bazaar] Cancelled! Refunded 64x Cobblestone from cancelling ..." (items back = a sell offer). */
    private static final Pattern CHAT_CANCEL_ITEMS = Pattern.compile(
            "(?i)\\[Bazaar\\]\\s+Cancelled!\\s+Refunded\\s+([\\d,]+)x\\s+(.+?)\\s+from\\s+cancelling");

    /** "[Bazaar] Cancelled! Refunded 70.4 coins from cancelling ..." (coins back = a buy order). */
    private static final Pattern CHAT_CANCEL_COINS = Pattern.compile(
            "(?i)\\[Bazaar\\]\\s+Cancelled!\\s+Refunded\\s+([\\d,.]+)\\s+coins?\\s+from\\s+cancelling");

    /**
     * Reads the Bazaar chat lines so the cache stays current <b>without reopening the orders menu</b>:
     * a "Setup!" line adds the order, "was filled" marks it filled, and "Claimed" / "Cancelled"
     * remove it. The menu scan stays the authority whenever it runs (it rebuilds from the real
     * slots); chat only keeps the time in between honest.
     */
    public void onChat(String raw) {
        try {
            String line = stripCodes(raw == null ? "" : raw).trim();
            String lower = line.toLowerCase(Locale.ROOT);
            if (!lower.contains("[bazaar]")) {
                return;
            }
            ensureLoaded();
            Matcher m = CHAT_SETUP.matcher(line);
            if (m.find()) {
                addFromChat(orderType(m.group(1)), parseInt(m.group(2)), m.group(3), parseNumber(m.group(4)));
                return;
            }
            m = CHAT_FILLED.matcher(line);
            if (m.find()) {
                markFilledFromChat(orderType(m.group(1)), m.group(3));
                return;
            }
            m = CHAT_CLAIM_SELL.matcher(line);
            if (m.find()) {
                removeFromChat(BazaarOrderType.SELL, parseInt(m.group(1)), m.group(2), parseNumber(m.group(3)));
                return;
            }
            m = CHAT_CLAIM_BUY.matcher(line);
            if (m.find()) {
                removeFromChat(BazaarOrderType.BUY, parseInt(m.group(1)), m.group(2), parseNumber(m.group(3)));
                return;
            }
            m = CHAT_CANCEL_ITEMS.matcher(line);
            if (m.find()) {
                removeFromChat(BazaarOrderType.SELL, parseInt(m.group(1)), m.group(2), -1);
                return;
            }
            m = CHAT_CANCEL_COINS.matcher(line);
            if (m.find()) {
                cancelBuyByRefund(parseNumber(m.group(1)));
                return;
            }
            logUnmatched(line);
        } catch (Throwable t) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][Bazaar] Chat order update failed", t);
        }
    }

    /**
     * Shapes of unmatched Bazaar lines already logged, so a busy session reports each new wording
     * once instead of every time it happens. Digits are what vary between two lines that are the
     * same sentence, so the shape is the line with its numbers collapsed.
     */
    private final Set<String> loggedUnmatched = ConcurrentHashMap.newKeySet();

    /** Cap, so a wording that somehow varies without digits cannot grow this without bound. */
    private static final int MAX_LOGGED_UNMATCHED = 40;

    /**
     * Reports a {@code [Bazaar]} line that no pattern claimed.
     *
     * <p><b>Every</b> unclaimed line, not the ones that look like order events. This used to fire
     * only when the text contained "setup!", "was filled", "claimed" or "cancelled" - which is to
     * say it could report a rewording of everything except the words it was matching on. The one
     * failure it existed to catch, Hypixel changing the wording of "Buy Order Setup!", was the one
     * it was structurally unable to see: the pattern stops matching and the keyword stops matching
     * in the same edit, and the order goes untracked in silence.
     *
     * <p>The {@code [Bazaar]} gate in {@link #onChat} already bounds this to Bazaar traffic, and
     * shapes are logged once each, so the cost of the wider net is a handful of lines per session.
     */
    private void logUnmatched(String line) {
        if (line == null || line.isBlank() || loggedUnmatched.size() >= MAX_LOGGED_UNMATCHED) {
            return;
        }
        String shape = line.replaceAll("[\\d,.]+", "#");
        if (loggedUnmatched.add(shape)) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Bazaar] Unmatched order chat line: '{}'", line);
        }
    }

    /** Adds a freshly set-up order (chat gives the TOTAL price, so the unit price is total/amount). */
    private void addFromChat(BazaarOrderType type, int amount, String name, double totalCoins) {
        if (type == null || amount <= 0 || totalCoins < 0) {
            return;
        }
        String itemName = name.trim();
        // The chat line carries the ROUNDED total ("70.4 coins" for 64x), so dividing it gives a unit
        // price that is wrong in the decimals - and on a Master Star those decimals are thousands of
        // coins. The confirmation menu states the real figure, so prefer it whenever it was seen.
        double exact = exactUnitPrice(type, amount, itemName);
        double unit = exact >= 0 ? exact : totalCoins / amount;
        BazaarOrder order = new BazaarOrder(chatItemId(itemName), itemName, type, unit, amount, -1);
        // The same order twice (double-fired chat hook) must not duplicate the row.
        for (BazaarOrder existing : orders) {
            if (existing.key().equals(order.key())) {
                return;
            }
        }
        List<BazaarOrder> next = new ArrayList<>(orders);
        next.add(order);
        orders = List.copyOf(next);
        persist(orders);
        // A buy order set up while a re-order flow is in flight is that flow completing: credit it
        // against the history row it started from, so the row clears itself.
        if (type == BazaarOrderType.BUY) {
            String rowKey = BazaarReorder.getInstance().creditRowFor(order.itemId());
            if (!rowKey.isEmpty()) {
                BazaarOrderHistory.getInstance().reduceRow(rowKey, amount);
            }
        }
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Bazaar] Chat: added {} {} x{} @ {} ({})",
                type, itemName, amount, unit, exact >= 0 ? "exact, from confirm menu" : "derived from chat total");
    }

    /** Marks the first matching non-filled order as FILLED (it stays listed until claimed). */
    private void markFilledFromChat(BazaarOrderType type, String name) {
        for (BazaarOrder order : orders) {
            if (order.type() == type && nameMatchesChat(order, name)
                    && order.status() != BazaarStatus.FILLED) {
                order.setStatus(BazaarStatus.FILLED);
                order.setLastNotifiedStatus(BazaarStatus.FILLED);
                persist(orders);
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Bazaar] Chat: {} {} marked filled",
                        type, order.itemName());
                return;
            }
        }
    }

    /**
     * Removes the claimed / cancelled order. When the chat line names a unit price ("at X each"),
     * the price-matching order is preferred, so two orders on the same product pick the right one.
     * A partial claim (claimed fewer units than the order holds) keeps the order listed.
     */
    private void removeFromChat(BazaarOrderType type, int amount, String name, double each) {
        BazaarOrder match = null;
        for (BazaarOrder order : orders) {
            if (order.type() != type || !nameMatchesChat(order, name)) {
                continue;
            }
            if (each > 0 && Math.abs(order.price() - each) < 0.5) {
                match = order;
                break; // exact price hit - it is this one
            }
            if (match == null) {
                match = order;
            }
        }
        if (match == null) {
            return;
        }
        if (amount > 0 && match.amount() > 0 && amount < match.amount()) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Bazaar] Chat: partial claim on {} {} ({} of {}) - kept",
                    type, match.itemName(), amount, match.amount());
            return;
        }
        List<BazaarOrder> next = new ArrayList<>(orders);
        next.remove(match);
        orders = List.copyOf(next);
        persist(orders);
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Bazaar] Chat: removed {} {} x{}",
                type, match.itemName(), match.amount());
    }

    /**
     * A cancelled buy order only refunds coins, never naming the item, so the order is identified by
     * its refund: the one buy order whose total (amount x unit) matches, or the only buy order there
     * is. Anything more ambiguous is left for the next menu scan (logged, never guessed).
     */
    private void cancelBuyByRefund(double refund) {
        List<BazaarOrder> buys = new ArrayList<>();
        for (BazaarOrder order : orders) {
            if (order.type() == BazaarOrderType.BUY) {
                buys.add(order);
            }
        }
        if (buys.isEmpty()) {
            return;
        }

        // Orders whose price divides the refund into a whole number of units they could still owe.
        // This is what makes a PARTIALLY FILLED cancel identifiable at all: the old test compared the
        // refund against price x amount, which is the escrow of an untouched order, so every partial
        // cancel failed it and fell through to "the only buy order there is".
        List<BazaarOrder> candidates = new ArrayList<>();
        for (BazaarOrder order : buys) {
            if (refundedUnits(order, refund) > 0) {
                candidates.add(order);
            }
        }

        // The detail screen the player just cancelled from is the strongest evidence there is, and
        // the only thing that separates two orders on the same product at different prices when both
        // happen to divide the refund.
        BazaarOrder match = null;
        OpenDetail detail = openDetail;
        if (detail != null && detail.live() && detail.type() == BazaarOrderType.BUY) {
            for (BazaarOrder order : candidates.isEmpty() ? buys : candidates) {
                if (order.itemId().equalsIgnoreCase(detail.itemId())
                        && BazaarOrder.ticks(order.price()) == BazaarOrder.ticks(detail.price())) {
                    match = order;
                    break;
                }
            }
        }
        if (match == null && candidates.size() == 1) {
            match = candidates.get(0);
        }
        if (match == null && candidates.isEmpty() && buys.size() == 1) {
            match = buys.get(0);
        }
        if (match == null) {
            SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Bazaar] Chat: buy cancel (refund {}) ambiguous among {} order(s) - kept",
                    refund, buys.size());
            return;
        }

        // The refund is the server's final word on what did not fill, taken after any purchase that
        // landed while the detail screen was open. It therefore beats the lore snapshot, which is
        // only used when the division does not come out whole (a stored price that is slightly off).
        int remaining = refundedUnits(match, refund);
        boolean fromRefund = remaining > 0;
        if (!fromRefund) {
            remaining = match.remaining();
        }

        List<BazaarOrder> next = new ArrayList<>(orders);
        next.remove(match);
        orders = List.copyOf(next);
        persist(orders);
        openDetail = null; // consumed - a later refund must not reuse this screen
        recordCancelledRemainder(match, remaining);
        copyCancelledAmount(match, remaining);
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Bazaar] Chat: removed cancelled BUY {} x{}, x{} still owed ({})",
                match.itemName(), match.amount(), remaining,
                fromRefund ? "from the refund" : "from the order lore - refund did not divide");
    }

    /**
     * How many units a refund of {@code refund} coins represents for this order, or 0 when it does
     * not represent a whole plausible number of them.
     *
     * <p>The tolerance scales with the unit count because the error does: a price derived from a
     * rounded chat total can sit up to half a tick off, and that error is multiplied by every unit.
     * A loose test here only ever costs an extra candidate, and an ambiguous cancel is left alone
     * rather than guessed at - so the failure mode is "kept", not "wrong row recorded".
     */
    private static int refundedUnits(BazaarOrder order, double refund) {
        double price = order.price();
        if (price <= 0 || refund <= 0) {
            return 0;
        }
        long units = Math.round(refund / price);
        if (units <= 0 || (order.amount() > 0 && units > order.amount())) {
            return 0;
        }
        double tolerance = Math.max(1.0, units * 0.06);
        return Math.abs(units * price - refund) <= tolerance ? (int) units : 0;
    }

    /**
     * Files the cancelled order's unfilled remainder in the {@link BazaarOrderHistory}, so it can be
     * re-placed in a click.
     *
     * <p>The filled count is derived from the remainder rather than carried over from the lore, for
     * the same reason the remainder is taken from the refund: it is the figure the cancellation
     * actually settled on.
     */
    private static void recordCancelledRemainder(BazaarOrder order, int remaining) {
        if (remaining <= 0) {
            return; // nothing left to re-place - a fully filled order is not history, it is a claim
        }
        int ordered = Math.max(order.amount(), remaining);
        BazaarOrderHistory.getInstance().record(new sbs.modid.client.economy.bazaar.model.CancelledOrder(
                order.itemId(), order.itemName(), remaining, ordered, ordered - remaining,
                order.price(), System.currentTimeMillis()));
    }

    /**
     * Puts the cancelled order's <b>unfilled remainder</b> on the clipboard, ready to paste into the
     * "custom amount" sign when re-placing it.
     *
     * <p>Only for BUY orders, and that is the whole point: a cancelled SELL offer says its amount
     * right there in chat ("Refunded 64x Cobblestone"), while a cancelled BUY order refunds coins
     * and never names the quantity - the one number you need to type back in is the one number
     * Hypixel does not tell you.
     *
     * <p>The remainder, not the order size. Pasting the original amount re-orders everything,
     * including the part that already filled and is sitting in the player's inventory - which is
     * the mistake this whole feature exists to stop them making by hand.
     */
    private void copyCancelledAmount(BazaarOrder order, int remaining) {
        if (!ConfigManager.getInstance().get().bazaar.copyCancelledAmount || remaining <= 0) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        // The chat hook can run off the render thread; the clipboard is a window resource.
        minecraft.execute(() -> {
            try {
                minecraft.keyboardHandler.setClipboard(Integer.toString(remaining));
            } catch (Throwable t) {
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Bazaar] could not copy the cancelled amount", t);
            }
        });
    }

    /** Case-insensitive match of a chat item name against a tracked order's display name. */
    private static boolean nameMatchesChat(BazaarOrder order, String name) {
        return order.itemName().trim().equalsIgnoreCase(name == null ? "" : name.trim());
    }

    /** The best-effort product id for a chat-only item name (the menu scan later supplies the real one). */
    private static String chatItemId(String name) {
        String byName = enchantmentIdFromName(name);
        if (byName != null) {
            return byName;
        }
        return name.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
    }

    private static BazaarOrderType orderType(String phrase) {
        return phrase != null && phrase.toLowerCase(Locale.ROOT).startsWith("sell")
                ? BazaarOrderType.SELL : BazaarOrderType.BUY;
    }

    private static double parseNumber(String text) {
        try {
            return Double.parseDouble(text.replace(",", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int parseInt(String text) {
        return (int) Math.round(Math.max(0, parseNumber(text)));
    }

    // ------------------------------------------------------------------
    // Slot parsing
    // ------------------------------------------------------------------

    private static BazaarOrder parseOrder(ItemStack stack, int slot) {
        String rawName = stripCodes(stack.getHoverName().getString());
        List<String> lore = extractLore(stack);

        BazaarOrderType type = detectType(rawName, lore);
        if (type == null) {
            return null; // not an order slot (decoration / navigation item)
        }
        double price = detectPrice(lore);
        if (price < 0) {
            return null; // no "Price per unit" line – not a real order
        }
        // The tooltip title is e.g. "SELL Venomous VI" / "BUY Diamond"; the display name is
        // just the item, so the BUY/SELL prefix is stripped off.
        String itemName = stripOrderPrefix(rawName);
        int amount = detectAmount(lore);
        String id = extractItemId(stack, itemName);
        BazaarOrder order = new BazaarOrder(id, itemName, type, price, amount, slot);
        // A 100% filled order is sold/bought out - there is nothing left to compete, so it must
        // never be flagged "outdated". Detect it here so the API sync leaves it alone.
        if (detectFilled(lore)) {
            order.setStatus(BazaarStatus.FILLED);
            order.setFilled(amount);
        } else {
            order.setFilled(detectFilledAmount(lore, amount));
        }
        return order;
    }

    /**
     * Parses how many units of an order have filled, from whichever shape the lore states it in.
     *
     * <p>Three shapes are accepted because the Bazaar menus do not agree on one, and because the
     * wording is Hypixel's to change: an explicit fraction ("Filled: 128/512"), a bare count
     * ("Filled: 128"), and a percentage ("75% filled"), in that order of preference - the fraction
     * says exactly what happened, while the percentage has already been rounded for display and can
     * only be multiplied back out.
     *
     * <p>Returns 0 when no fill line is found. That is the safe direction to be wrong in: it makes
     * the remainder the whole order, so the player is offered too much to re-place rather than too
     * little, and too much is visible on the confirmation screen while too little is not.
     */
    private static int detectFilledAmount(List<String> lore, int amount) {
        for (String line : lore) {
            String l = line.toLowerCase(Locale.ROOT);
            if (!l.contains("filled") && !l.contains("fill:")) {
                continue;
            }
            Matcher fraction = FILLED_FRACTION.matcher(line);
            if (fraction.find()) {
                return clampFilled(parseInt(fraction.group(1)), amount);
            }
            Matcher percent = FILLED_PERCENT.matcher(line);
            if (percent.find() && amount > 0) {
                double pct = parseNumber(percent.group(1));
                if (pct >= 0) {
                    return clampFilled((int) Math.round(amount * Math.min(pct, 100.0) / 100.0), amount);
                }
            }
            Matcher count = NUMBER.matcher(line);
            if (count.find()) {
                return clampFilled(parseInt(count.group(1)), amount);
            }
        }
        return 0;
    }

    /** "Filled: 128/512" / "128 / 512 filled" - the numerator is what has been bought. */
    private static final Pattern FILLED_FRACTION =
            Pattern.compile("([0-9][0-9,]*)\\s*/\\s*[0-9][0-9,]*");

    /** "75% filled" / "Filled: 75%". */
    private static final Pattern FILLED_PERCENT =
            Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*%");

    private static int clampFilled(int filled, int amount) {
        if (amount <= 0) {
            return Math.max(0, filled);
        }
        return Math.clamp(filled, 0, amount);
    }

    /** Whether the order lore says it is fully filled ("§a§l100% FILLED!" / "Filled: 100%"). */
    private static boolean detectFilled(List<String> lore) {
        for (String line : lore) {
            String l = line.toLowerCase(Locale.ROOT);
            if (l.contains("100%") && l.contains("fill")) {
                return true;
            }
        }
        return false;
    }

    /** Removes a leading {@code BUY}/{@code SELL} word from the tooltip title. */
    private static String stripOrderPrefix(String name) {
        String trimmed = name.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("buy ")) {
            return trimmed.substring(4).trim();
        }
        if (lower.startsWith("sell ")) {
            return trimmed.substring(5).trim();
        }
        return trimmed;
    }

    /** Parses the unit count from the "Order amount: 64x" / "Offer amount: 1x" line. */
    private static int detectAmount(List<String> lore) {
        for (String line : lore) {
            String l = line.toLowerCase(Locale.ROOT);
            if (l.contains("order amount") || l.contains("offer amount")) {
                Matcher m = NUMBER.matcher(line);
                if (m.find()) {
                    try {
                        return (int) Math.round(Double.parseDouble(m.group(1).replace(",", "")));
                    } catch (NumberFormatException ignored) {
                        return 0;
                    }
                }
            }
        }
        return 0;
    }

    private static BazaarOrderType detectType(String name, List<String> lore) {
        // Prefer the explicit phrases Hypixel uses, then fall back to a bare buy/sell word.
        for (String line : iterate(name, lore)) {
            String l = line.toLowerCase(Locale.ROOT);
            if (l.contains("sell offer")) {
                return BazaarOrderType.SELL;
            }
            if (l.contains("buy order")) {
                return BazaarOrderType.BUY;
            }
        }
        for (String line : iterate(name, lore)) {
            String l = line.toLowerCase(Locale.ROOT);
            if (l.contains("sell")) {
                return BazaarOrderType.SELL;
            }
            if (l.contains("buy")) {
                return BazaarOrderType.BUY;
            }
        }
        return null;
    }

    private static double detectPrice(List<String> lore) {
        for (String line : lore) {
            if (line.toLowerCase(Locale.ROOT).contains("per unit")) {
                Matcher m = NUMBER.matcher(line);
                if (m.find()) {
                    try {
                        return Double.parseDouble(m.group(1).replace(",", ""));
                    } catch (NumberFormatException ignored) {
                        return -1;
                    }
                }
            }
        }
        return -1;
    }

    private static String extractItemId(ItemStack stack, String fallbackName) {
        try {
            Object component = stack.get(DataComponents.CUSTOM_DATA);
            if (component instanceof CustomData data) {
                CompoundTag tag = data.copyTag();
                CompoundTag extra = tag.getCompoundOrEmpty("ExtraAttributes");
                String id = extra.getStringOr("id", "");
                if (id.isEmpty()) {
                    id = tag.getStringOr("id", ""); // modern flattened custom data
                }
                // Enchantment books (incl. ultimate enchants) carry the GENERIC id
                // ENCHANTED_BOOK in NBT, but the Bazaar API keys them
                // ENCHANTMENT_<NAME>_<LEVEL> (single enchant + level). Resolve that so
                // BazaarSyncService finds the product and can flag the order outdated.
                if ("ENCHANTED_BOOK".equalsIgnoreCase(id)) {
                    String bazaarId = enchantmentProductId(extra.getCompoundOrEmpty("enchantments"));
                    if (bazaarId != null) {
                        return bazaarId;
                    }
                }
                if (!id.isEmpty()) {
                    return id;
                }
            }
        } catch (Throwable ignored) {
            // fall through to the name-based fallback
        }
        // Name fallback also understands enchant books ("Ultimate Legion V" ->
        // ENCHANTMENT_ULTIMATE_LEGION_5) for the rare slots without usable NBT.
        String byName = enchantmentIdFromName(fallbackName);
        if (byName != null) {
            return byName;
        }
        return fallbackName.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
    }

    /** {@code ENCHANTMENT_<NAME>_<LEVEL>} from a one-entry {@code enchantments} compound, else null. */
    private static String enchantmentProductId(CompoundTag enchantments) {
        for (String key : enchantments.keySet()) {
            int level = enchantments.getIntOr(key, 0);
            if (level > 0) {
                return "ENCHANTMENT_" + key.toUpperCase(Locale.ROOT) + "_" + level;
            }
        }
        return null;
    }

    /** Trailing Roman numerals 1..10, for the enchant-book name fallback. */
    private static final Map<String, Integer> ROMAN = Map.ofEntries(
            Map.entry("I", 1), Map.entry("II", 2), Map.entry("III", 3), Map.entry("IV", 4),
            Map.entry("V", 5), Map.entry("VI", 6), Map.entry("VII", 7), Map.entry("VIII", 8),
            Map.entry("IX", 9), Map.entry("X", 10));


    /**
     * "Ultimate Legion V" / "Sharpness 6" / "Wisdom V" / "Duplex V" ->
     * {@code ENCHANTMENT_ULTIMATE_LEGION_5} / {@code ENCHANTMENT_SHARPNESS_6} /
     * {@code ENCHANTMENT_ULTIMATE_WISDOM_5} / {@code ENCHANTMENT_ULTIMATE_REITERATE_5}.
     * Returns null when the name has no trailing level, so ordinary items are never
     * misidentified as enchantment products.
     */
    private static String enchantmentIdFromName(String name) {
        String trimmed = name == null ? "" : name.trim().replaceAll("^[^A-Za-z]+", "").trim();
        int lastSpace = trimmed.lastIndexOf(' ');
        if (lastSpace <= 0) {
            return null;
        }
        String head = trimmed.substring(0, lastSpace).trim();
        String tail = trimmed.substring(lastSpace + 1).trim();
        Integer level = null;
        if (tail.chars().allMatch(Character::isDigit) && !tail.isEmpty()) {
            level = Integer.parseInt(tail);
        } else {
            level = ROMAN.get(tail.toUpperCase(Locale.ROOT));
        }
        if (level == null || level < 1 || level > 10 || head.isEmpty()) {
            return null;
        }
        String base = resolveEnchantBase(
                head.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_"));
        return "ENCHANTMENT_" + base.toUpperCase(Locale.ROOT) + "_" + level;
    }

    /** Display base name -> real NBT/product enchant name (shared mapping, registry-validated). */
    private static String resolveEnchantBase(String base) {
        return sbs.modid.client.helper.enchants.EnchantNames.resolveBase(base);
    }

    private static List<String> extractLore(ItemStack stack) {
        try {
            Object component = stack.get(DataComponents.LORE);
            if (component instanceof ItemLore lore) {
                List<String> lines = new ArrayList<>();
                for (Object line : lore.lines()) {
                    if (line instanceof Component text) {
                        lines.add(stripCodes(text.getString()));
                    }
                }
                return lines;
            }
        } catch (Throwable ignored) {
        }
        return List.of();
    }

    private static List<String> iterate(String name, List<String> lore) {
        List<String> all = new ArrayList<>(lore.size() + 1);
        all.add(name);
        all.addAll(lore);
        return all;
    }

    /** Strips Minecraft section-sign formatting codes (e.g. the colour prefix on names). */
    private static String stripCodes(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll(SECTION_SIGN + ".", "");
    }
}
