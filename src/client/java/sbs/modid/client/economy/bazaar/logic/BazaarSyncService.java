/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.bazaar.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.social.chat.logic.SBSChat;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.economy.bazaar.model.BazaarOrder;
import sbs.modid.client.economy.bazaar.model.BazaarOrderType;
import sbs.modid.client.economy.bazaar.model.BazaarSnapshot;
import sbs.modid.client.economy.bazaar.model.BazaarStatus;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background service that keeps every tracked Bazaar order's {@link BazaarStatus} in sync
 * with the live Hypixel Bazaar API.
 *
 * <p>Runs on its own daemon thread (mirroring {@code ApiServer}'s threading), every ~5
 * seconds, completely independently of whether the Bazaar GUI is open. To avoid needless
 * requests it skips the cycle entirely when there are no tracked orders or the player is
 * not in a world. The single fetched snapshot is reused for every order, so one cycle is
 * one request.
 *
 * <p>Status is written back onto the (volatile) {@link BazaarOrder#setStatus} so the
 * highlight overlay sees it immediately; chat notifications are sent on the client thread
 * via {@link Minecraft#execute(Runnable)} and only when the status actually changes (the
 * first evaluation each launch is applied silently to avoid a burst on startup).
 */
public final class BazaarSyncService {

    private static final BazaarSyncService INSTANCE = new BazaarSyncService();

    private static final long PERIOD_SECONDS = 5;

    /**
     * How long an order may legitimately read as beating the entire order book (3 source rotations).
     * Past this it is treated as bad data rather than a winning order — see {@link #evaluate}.
     */
    private static final long STRICTLY_BETTER_GRACE_MS = 60_000L;

    /**
     * Minimum spacing between our own pull attempts. Deliberately short: the real gate is
     * {@link BazaarSnapshot}'s rotation check, which only lets a fetch through once Hypixel is due to
     * have published a new generation, so a small value here costs nothing and just means we pick that
     * generation up promptly.
     *
     * <p>Was 30s under the belief that the source refreshed every ~60s. Measured 2026-07-25: it
     * refreshes every <b>~20s</b> ({@code lastUpdated} stepped 394182 → 414065 → 434148 → 454183). A
     * 30s window is longer than the source's own cycle, so entire generations were being skipped —
     * order statuses were compared against data that had already been superseded twice.
     */
    private static final long SNAPSHOT_MAX_AGE_MS = 5_000L;

    /** Hypixel's own command for the orders menu, so the ORDERS link is one command and no clicks. */
    private static final String ORDERS_COMMAND = "/managebazaarorders";


    private ScheduledExecutorService scheduler;

    private BazaarSyncService() {
    }

    public static BazaarSyncService getInstance() {
        return INSTANCE;
    }

    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "SBS-Bazaar-Sync");
            thread.setDaemon(true);
            return thread;
        });
        // Fixed delay (not rate) so a slow request never overlaps the next cycle.
        scheduler.scheduleWithFixedDelay(this::syncSafely, PERIOD_SECONDS, PERIOD_SECONDS, TimeUnit.SECONDS);
        // Update wave: re-evaluate the moment ANY consumer refreshes the shared snapshot, instead of
        // waiting out the rest of our own tick on data we already know is superseded.
        BazaarSnapshot.getInstance().addListener(this::onSnapshot);
        SkyblockSimplifiedSBS.LOGGER.info("[SBS] Bazaar API sync running every {}s (+ snapshot update wave).",
                PERIOD_SECONDS);
    }

    /** Update-wave entry point: a fresh snapshot landed, so every tracked order is re-checked now. */
    private void onSnapshot(BazaarApiClient.Response response) {
        try {
            if (Minecraft.getInstance().player == null) {
                return;
            }
            evaluateAll(response);
        } catch (Throwable t) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS] Bazaar wave evaluation failed", t);
        }
    }

    private void syncSafely() {
        try {
            sync();
        } catch (Throwable t) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS] Bazaar sync cycle failed", t);
        }
    }

    private void sync() {
        List<BazaarOrder> orders = BazaarOrderTracker.getInstance().getOrders();
        if (orders.isEmpty()) {
            return; // nothing to track – don't hit the API
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return; // not in a world
        }

        // Shared store: reuse any pull made recently (by us or the price cache) instead of firing our
        // own - one Bazaar request stream serves every module. The snapshot now comes straight from
        // Hypixel and carries the full order book, which is what evaluate() compares against.
        BazaarSnapshot snapshot = BazaarSnapshot.getInstance();
        long generationBefore = snapshot.generation();
        BazaarApiClient.Response response = snapshot.get(SNAPSHOT_MAX_AGE_MS);
        if (response == null) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Bazaar] API request returned no usable data this cycle.");
            return;
        }
        if (snapshot.generation() != generationBefore) {
            return; // this call did the fetch - the update wave has already re-evaluated everything
        }
        // Snapshot was still fresh, so no wave fired: check the orders against the cached copy, which
        // is what catches an order placed since the last refresh.
        evaluateAll(response);
    }

    /**
     * Compares every tracked order against one snapshot and writes back the resulting status.
     *
     * <p>Called from both drivers: the {@value #PERIOD_SECONDS}s tick (for orders that changed while
     * the snapshot stayed fresh) and the update wave (for a snapshot that changed while the orders
     * stayed the same). The {@code generation} check in {@link #sync()} keeps exactly one of the two
     * from running per cycle, so a snapshot is never processed twice.
     */
    private void evaluateAll(BazaarApiClient.Response response) {
        List<BazaarOrder> orders = BazaarOrderTracker.getInstance().getOrders();
        if (orders.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        boolean chatEnabled = ConfigManager.getInstance().get().bazaar.sendChatInfo;
        List<Component> messages = new ArrayList<>();
        // Two ages, because they fail differently: "snapshot" is how long ago WE pulled, "data" is
        // Hypixel's own lastUpdated. A large data age with a small snapshot age means the prices are
        // stale at the source (Cloudflare's ~60s cache) and no amount of local refreshing helps.
        long dataAgeMs = response.lastUpdated > 0
                ? System.currentTimeMillis() - response.lastUpdated : -1;
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Bazaar] Comparing {} order(s) against the API (snapshot age {}ms, data age {}ms).",
                orders.size(), BazaarSnapshot.getInstance().ageMs(), dataAgeMs);

        for (BazaarOrder order : orders) {
            if (order.status() == BazaarStatus.FILLED) {
                continue; // 100% filled - sold out, nothing to compete, never mark it outdated
            }
            BazaarApiClient.Product product = resolveProduct(response, order.itemId());
            if (product == null) {
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Bazaar] No API product for id='{}' ({}).",
                        order.itemId(), order.itemName());
                continue; // unknown to the API this cycle – keep the previous status
            }
            BazaarStatus newStatus = evaluate(order, product);
            // The real best competing order (top of the order book), for the log.
            boolean buy = order.type() == BazaarOrderType.BUY;
            BazaarApiClient.Summary best = bestLevel(buy ? product.sell_summary : product.buy_summary, buy);
            SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Bazaar] {} \"{}\" myPrice={} bestBook={} (orders={}) -> {}",
                    order.type(), order.itemName(), order.price(),
                    best != null ? best.pricePerUnit : -1,
                    best != null ? best.orders : 0, newStatus);
            if (newStatus == BazaarStatus.UNKNOWN) {
                continue; // no usable market data – keep the previous status, don't notify
            }
            order.setStatus(newStatus);

            // Notify on every real status change, including the first time it becomes known
            // (previous is null on a fresh launch). Unchanged statuses are never repeated.
            BazaarStatus previous = order.lastNotifiedStatus();
            if (newStatus != previous) {
                order.setLastNotifiedStatus(newStatus);
                if (chatEnabled) {
                    messages.add(message(order, newStatus));
                }
            }
        }

        if (!messages.isEmpty()) {
            minecraft.execute(() -> {
                if (minecraft.player == null) {
                    return;
                }
                for (Component text : messages) {
                    minecraft.player.sendSystemMessage(text);
                }
            });
        }
    }

    /**
     * Compares a stored order against the <b>top of the live order book</b> ({@code buy_summary} /
     * {@code sell_summary}), NOT the aggregate {@code quick_status}.
     *
     * <p><b>Why the order book, not quick_status:</b> {@code quick_status.buyPrice}/{@code sellPrice}
     * are Hypixel's <i>weighted-average</i> prices across a slice of the book, not the single best
     * order, so comparing against them made an order read "Best Offer" while real orders undercut it.
     *
     * <p><b>Hypixel's summaries are named inside-out</b> (verified 2026-07-23): {@code buy_summary}
     * actually holds the <i>sell offers</i> (the prices you BUY at) and {@code sell_summary} holds the
     * <i>buy orders</i> (the prices you SELL at). So a player's competition is:
     * <ul>
     *   <li>a <b>BUY order</b> competes with the other buy orders → the <b>highest</b> price in
     *       {@code sell_summary};</li>
     *   <li>a <b>SELL offer</b> competes with the other sell offers → the <b>lowest</b> price in
     *       {@code buy_summary}.</li>
     * </ul>
     * Feeding the wrong summary made every order compare against the opposite side of the spread and
     * always read OUTDATED even when it was top of book.
     *
     * <p>The best level's order count includes the player's own order, so it also separates
     * {@link BazaarStatus#BEST_OFFER} (alone at the top) from {@link BazaarStatus#MATCHED} (sharing
     * the top price with someone else):
     * <ul>
     *   <li>strictly ahead of the whole book → {@link BazaarStatus#BEST_OFFER} (a just-placed order
     *       the snapshot has not caught up to yet);</li>
     *   <li>at the best price, alone there → {@link BazaarStatus#BEST_OFFER};</li>
     *   <li>at the best price, shared → {@link BazaarStatus#MATCHED};</li>
     *   <li>behind the best price → {@link BazaarStatus#OUTDATED}.</li>
     * </ul>
     */
    static BazaarStatus evaluate(BazaarOrder order, BazaarApiClient.Product product) {
        boolean buy = order.type() == BazaarOrderType.BUY;
        BazaarApiClient.Summary best = bestLevel(buy ? product.sell_summary : product.buy_summary, buy);
        if (best == null || best.pricePerUnit <= 0) {
            return BazaarStatus.UNKNOWN; // no usable order book this cycle - keep the previous status
        }
        double price = order.price();
        double reference = best.pricePerUnit;
        // Both sides as whole 0.1-coin ticks, so this is EXACT integer arithmetic - no tolerance at
        // all. An undercut is always a whole number of ticks, so "tied" means the same tick and
        // "behind" means at least one tick behind; there is no grey zone left to mis-size.
        long myTicks = BazaarOrder.ticks(price);
        long bestTicks = BazaarOrder.ticks(reference);
        boolean strictlyBetter = buy ? myTicks > bestTicks : myTicks < bestTicks;
        if (strictlyBetter) {
            // Beating the WHOLE book is only legitimate for the ~20s until the snapshot catches up to
            // a just-placed order - after that our own order IS the reference. A run longer than that
            // means the comparison is built on something false: a misparsed price (a truncated
            // millions figure reads as unbeatable at every future cycle), or an order that was
            // claimed/cancelled without a matching chat line and no longer exists. Both used to sit
            // here reporting "Best Offer" forever, which is the worst possible failure for this
            // feature - it looks exactly like a correct answer. Report UNKNOWN ("…") instead: no claim
            // is far better than a confident wrong one, and the log names the order to fix.
            long now = System.currentTimeMillis();
            if (order.strictlyBetterSince() == 0L) {
                order.setStrictlyBetterSince(now);
            } else if (now - order.strictlyBetterSince() > STRICTLY_BETTER_GRACE_MS) {
                SkyblockSimplifiedSBS.LOGGER.warn(
                        "[SBS][Bazaar] {} \"{}\" has beaten the entire book for {}s "
                                + "(myPrice={}, bestBook={}). The stored price is probably misparsed, "
                                + "or this order no longer exists. Reporting UNKNOWN instead of Best Offer.",
                        order.type(), order.itemName(), (now - order.strictlyBetterSince()) / 1000,
                        price, reference);
                return BazaarStatus.UNKNOWN;
            }
            return BazaarStatus.BEST_OFFER;
        }
        order.setStrictlyBetterSince(0L);
        if (myTicks == bestTicks) {
            return best.orders <= 1 ? BazaarStatus.BEST_OFFER : BazaarStatus.MATCHED;
        }
        return BazaarStatus.OUTDATED; // at least one competing order sits a whole tick ahead of ours
    }

    /**
     * The best competing order level in a summary: the highest-price buy order, or the lowest-price
     * sell offer. Scans for the extreme instead of trusting index 0, so it stays correct regardless
     * of the list's order. {@code null} when the book is empty this cycle.
     */
    private static BazaarApiClient.Summary bestLevel(List<BazaarApiClient.Summary> book, boolean buy) {
        if (book == null) {
            return null;
        }
        BazaarApiClient.Summary best = null;
        for (BazaarApiClient.Summary level : book) {
            if (level == null || level.pricePerUnit <= 0) {
                continue;
            }
            if (best == null || (buy ? level.pricePerUnit > best.pricePerUnit
                    : level.pricePerUnit < best.pricePerUnit)) {
                best = level;
            }
        }
        return best;
    }

    /**
     * Builds the colored notification line: {@code [SBS]} in the accent color, the item name and
     * surrounding text in white, and only the status word ("Best Offer" / "Matched" / "Outdated")
     * in its status color.
     *
     * <p>The line ends in two separately clickable words - "Click here for ORDERS or ITEM!" - because
     * being told an order was undercut is only half of it: the next thing you do is either look at
     * your orders or look at that item's page, and both were a menu walk away. ORDERS runs Hypixel's
     * own {@value #ORDERS_COMMAND}; ITEM runs {@code /bz <name>}. One click, one command, nothing
     * clicked on the player's behalf inside the menu that opens.
     */
    private static Component message(BazaarOrder order, BazaarStatus status) {
        String connector = status == BazaarStatus.BEST_OFFER ? "is now" : "got";
        String query = bazaarQuery(order);
        MutableComponent body = Component
                .literal(" Bazaar Item \"" + order.itemName() + "\" " + connector + " ")
                .withColor(SBSChat.WHITE)
                .append(Component.literal(status.displayName()).withColor(statusColor(status)))
                .append(Component.literal(". Click here for ").withColor(SBSChat.WHITE))
                .append(link("ORDERS", ORDERS_COMMAND, "Open your Bazaar orders"));
        // No usable search text (a name that was all symbols, or none at all): the ITEM half would
        // open an empty search, so it is left off rather than offered and broken.
        if (!query.isEmpty()) {
            body.append(Component.literal(" or ").withColor(SBSChat.WHITE))
                    .append(link("ITEM", "/bz " + query, "Open " + query + " on the Bazaar"));
        }
        body.append(Component.literal("!").withColor(SBSChat.WHITE));
        return SBSChat.line(body);
    }

    /** One clickable word in the notification: accent-colored, underlined, with a hover hint. */
    private static MutableComponent link(String label, String command, String hover) {
        return Component.literal(label)
                .withColor(SBSChat.PREFIX_COLOR)
                .withStyle(style -> style
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent.RunCommand(command))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
    }

    /** The text the ITEM link searches for: the one shared rule in {@link BazaarSearch}. */
    private static String bazaarQuery(BazaarOrder order) {
        return BazaarSearch.query(order.itemId(), order.itemName());
    }

    /**
     * The API product for an order id, tolerant of the two shard id conventions. Attribute shards
     * are keyed {@code SHARD_<NAME>} on the Bazaar (SHARD_TIDE), but the order item's name fallback
     * produces {@code <NAME>_SHARD} (TIDE_SHARD) when its NBT id is unreadable - so most shard orders
     * missed their product. Non-attribute shards (PRISMARINE_SHARD, GLACITE_SHARD) are keyed the
     * other way and resolve on the first, direct hit. Both directions are tried so either form finds
     * its product.
     */
    private static BazaarApiClient.Product resolveProduct(BazaarApiClient.Response response, String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        BazaarApiClient.Product product = response.products.get(id);
        if (product != null) {
            return product;
        }
        String upper = id.toUpperCase(java.util.Locale.ROOT);
        if (upper.endsWith("_SHARD")) {
            return response.products.get("SHARD_" + upper.substring(0, upper.length() - "_SHARD".length()));
        }
        if (upper.startsWith("SHARD_")) {
            return response.products.get(upper.substring("SHARD_".length()) + "_SHARD");
        }
        return null;
    }

    /** RGB (no alpha) status color for chat text, matching the in-GUI highlight colors. */
    private static int statusColor(BazaarStatus status) {
        return switch (status) {
            case BEST_OFFER -> SBSTheme.BAZAAR_BEST_FRAME & 0xFFFFFF;   // green
            case MATCHED -> SBSTheme.BAZAAR_MATCHED_FRAME & 0xFFFFFF;   // orange
            case OUTDATED -> SBSTheme.BAZAAR_OUTDATED_FRAME & 0xFFFFFF; // red
            default -> SBSChat.WHITE;
        };
    }
}
