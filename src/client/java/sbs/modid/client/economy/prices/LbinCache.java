/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.prices;

import net.minecraft.client.Minecraft;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.item.SkyblockItem;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background aggregator + cache of Lowest-BIN prices, keyed by SkyBlock item id.
 *
 * <p>On its own daemon thread (mirroring {@link sbs.modid.client.economy.bazaar.logic.BazaarSyncService}) it walks
 * every page of the auctions endpoint, decodes each BIN auction's {@code item_bytes} to its SkyBlock
 * id, and keeps the minimum {@code starting_bid} per id. The whole map is swapped in atomically
 * (a {@code volatile} reference to an immutable snapshot), so tooltip reads are always consistent and
 * lock-free.
 *
 * <p>To avoid needless load and rate-limiting it only refreshes while the "Show LBIN" setting is on
 * and the player is in a world, and no more often than {@link #PERIOD_MINUTES}. It never runs on or
 * blocks the client thread.
 */
public final class LbinCache {

    private static final LbinCache INSTANCE = new LbinCache();

    /**
     * Cache lifetime (fixed delay after each completed crawl). Note the crawl itself spans many
     * pages, so with the configured API key this sits at the edge of the rate limit by design.
     */
    private static final long PERIOD_SECONDS = 60;

    /** Safety cap on pages fetched per cycle (the endpoint currently has far fewer). */
    private static final int MAX_PAGES = 200;

    private final LbinApiClient api = new LbinApiClient();
    private ScheduledExecutorService scheduler;

    private volatile Map<String, Long> lbin = Map.of();

    /**
     * The index being built by the crawl in progress. Published whole at the end of a crawl rather
     * than incrementally like {@link #lbin}: a half-built index would report "this is the only
     * listing" for items whose other listings are on a page not read yet, which is exactly the
     * shape that reads as a huge flip.
     */
    private LiveAuctionIndex.Builder building;

    private LbinCache() {
    }

    public static LbinCache getInstance() {
        return INSTANCE;
    }

    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "SBS-LBIN-Sync");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::refreshSafely, 10, PERIOD_SECONDS, TimeUnit.SECONDS);
        SkyblockSimplifiedSBS.LOGGER.info("[SBS] LBIN cache refresh scheduled every {} s (when enabled).", PERIOD_SECONDS);
    }

    /** Lowest BIN for a SkyBlock id, or {@code null} if unknown. */
    public Long getLbin(String skyblockId) {
        return skyblockId == null ? null : lbin.get(skyblockId);
    }

    /** Kicks off a refresh right now (used when the "Show LBIN" toggle is switched on). */
    public synchronized void requestRefresh() {
        if (scheduler != null) {
            scheduler.execute(this::refreshSafely);
        }
    }

    private void refreshSafely() {
        try {
            // Refresh while any consumer needs AH prices: the LBIN tooltip, the fishing profit
            // tracker (it values AH-only drops like armor pieces by lowest BIN), or the minion
            // calculator while its screen is open (AH-only minion outputs).
            var cfg = ConfigManager.getInstance().get();
            boolean fishingNeedsIt = cfg.fishing.enabled && cfg.fishing.profitTracker;
            boolean minionsNeedIt = sbs.modid.client.economy.minions.logic.MinionCalcService.wantsAhPrices();
            // The Nucleus Run tracker values AH-only bundle drops (Pickonimbus, Prehistoric Egg).
            boolean nucleusNeedsIt = cfg.nucleusRun.enabled;
            // The local AH flip ranking and the local Similar Auctions list both read the live
            // index this crawl builds, so either one wanting it is a reason to run.
            boolean localAuctionsNeedIt =
                    (cfg.ahFlips.enabled && cfg.ahFlips.flipSource.preferLocal)
                            || cfg.ahFlips.similarSource.preferLocal;
            if (!cfg.itemOverlay.showLbin && !fishingNeedsIt && !minionsNeedIt
                    && !localAuctionsNeedIt && !nucleusNeedsIt) {
                return; // no consumer – don't hit the API
            }
            if (Minecraft.getInstance().player == null) {
                return; // not in a world
            }
            refresh();
        } catch (Throwable t) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][LBIN] Refresh cycle failed", t);
        }
    }

    private void refresh() {
        LbinApiClient.Response first = api.fetch(0);
        if (first == null) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][LBIN] Auctions API returned no usable data this cycle.");
            return;
        }
        Map<String, Long> map = new HashMap<>();
        building = new LiveAuctionIndex.Builder();
        accumulate(first, map);
        this.lbin = Map.copyOf(map); // publish immediately – first prices show within seconds

        int totalPages = Math.min(first.totalPages, MAX_PAGES);
        for (int page = 1; page < totalPages; page++) {
            LbinApiClient.Response response = api.fetch(page);
            if (response != null) {
                accumulate(response, map);
            }
            // Publish incrementally so tooltips fill in while the (long) crawl is still running.
            if (page % 10 == 0) {
                this.lbin = Map.copyOf(map);
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][LBIN] Progress: {} / {} page(s), {} item(s) so far.",
                        page, totalPages, map.size());
            }
        }

        this.lbin = Map.copyOf(map);
        building.publish(System.currentTimeMillis());
        building = null;
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][LBIN] Cached lowest BIN for {} item(s) across {} page(s); live index holds {}.",
                map.size(), totalPages, LiveAuctionIndex.getInstance().snapshot().size());
    }

    /**
     * Matching is by the auction's {@code item_name} (normalised), NOT by decoding
     * {@code item_bytes}: the bytes are legacy-format NBT whose decode can fail silently on modern
     * clients, which left custom items priceless. The hovered item's display name is normalised
     * with the exact same function, so both sides always meet.
     */
    private void accumulate(LbinApiClient.Response response, Map<String, Long> map) {
        for (LbinApiClient.Auction auction : response.auctions) {
            if (!auction.bin || auction.starting_bid <= 0) {
                continue;
            }
            String key = SkyblockItem.normalizeName(auction.item_name);
            if (key.isEmpty()) {
                continue;
            }
            map.merge(key, auction.starting_bid, Math::min);
            if (building != null) {
                String id = auctionId(auction.uuid);
                if (id != null) {
                    building.offer(key, auction.item_name,
                            new LiveAuctionIndex.Listing(id, auction.starting_bid,
                                    Math.max(0, auction.end)));
                }
            }
        }
    }

    /**
     * An auction id in the exact form {@code /viewauction} takes, or {@code null}.
     *
     * <p>Validated here, at the point the untrusted field enters the mod, rather than at the point a
     * command is built from it - so there is one place to check rather than one per consumer, and a
     * listing that could not produce a valid command never reaches the index at all.
     */
    private static String auctionId(String raw) {
        if (raw == null) {
            return null;
        }
        String id = raw.toLowerCase(java.util.Locale.ROOT).replace("-", "");
        return AUCTION_ID.matcher(id).matches() ? id : null;
    }

    /** Hypixel auction ids are exactly 32 lowercase hex chars - anything else is refused. */
    private static final java.util.regex.Pattern AUCTION_ID =
            java.util.regex.Pattern.compile("[0-9a-f]{32}");
}
