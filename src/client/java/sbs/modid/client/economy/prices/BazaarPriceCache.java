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
import sbs.modid.client.economy.bazaar.logic.BazaarApiClient;
import sbs.modid.client.economy.bazaar.model.BazaarSnapshot;
import sbs.modid.client.core.config.ConfigManager;

import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background cache of the lowest Bazaar price per SkyBlock item id.
 *
 * <p>Reuses the existing {@link BazaarApiClient} (one request per cycle returns the whole Bazaar
 * snapshot) and, like {@link LbinCache}, keeps a {@code volatile} immutable {@code id -> price} map
 * that tooltip reads consult lock-free. "Lowest Bazaar Price" is the instant-buy price
 * ({@code quick_status.buyPrice} – the lowest sell offer), i.e. the cheapest you can acquire the item
 * for right now.
 *
 * <p><b>It refreshes while somebody is reading it, not while a listed setting is on.</b> This used to
 * hold a hand-written list of the config flags whose features want Bazaar prices, and that list was
 * the bug: it named four of the twenty-odd consumers, so with a stock config the map stayed
 * permanently empty and <i>every Bazaar-only item priced as unknown everywhere at once</i> - the
 * Hunting Box, the Attribute Menu and the tooltip alike - while auction items carried on pricing
 * fine, because {@link LbinCache} is warm by default for unrelated reasons. Attribute shards are
 * Bazaar-only, so "shards never have a price and everything else does" was the whole symptom of an
 * empty map. A whitelist that has to be extended every time a feature starts pricing something is a
 * whitelist that will be wrong again, so there is no longer one: every read marks demand, and demand
 * is what keeps the refresh running. A player using no Bazaar feature reads nothing and still costs
 * no request, which is the property the whitelist was there for.
 *
 * <p>Runs on its own daemon thread, never the client thread.
 */
public final class BazaarPriceCache {

    private static final BazaarPriceCache INSTANCE = new BazaarPriceCache();

    /** Cache lifetime – one background request per cycle refreshes the whole product map. */
    private static final long PERIOD_SECONDS = 60;

    /**
     * How long a read keeps the map warm. Comfortably longer than the refresh period, so a feature
     * that consults prices while a screen is open does not lapse between two of its own reads.
     */
    private static final long DEMAND_WINDOW_MS = 5 * 60_000L;

    /**
     * How often a read against an empty map may ask for an out-of-band refresh.
     *
     * <p>Without this the first consumer of the session waits up to a full period for a map it just
     * asked for - which on the Hunting Box means opening the box, seeing every shard unpriced, and
     * having no way to tell that from the feature being broken.
     */
    private static final long COLD_KICK_MS = 15_000L;

    /** Both quick_status figures for one product: instant-buy and instant-sell. */
    public record BzPrice(long buy, long sell) {
    }

    /**
     * How much of a product actually moves, kept beside the price because a price alone cannot say
     * whether you can get it.
     *
     * <p>Separate from {@link BzPrice} rather than added to it: every existing consumer wants a
     * price and nothing else, and widening the record they all destructure would have been a change
     * to code that has no interest in volume. The weekly figures are the throughput ones - standing
     * order depth says what is resting on the book right now, which a single sell can clear.
     *
     * @param buyWeek   units bought over the last 7 days
     * @param sellWeek  units sold over the last 7 days
     * @param buyOrders how many distinct buy orders make up the book
     * @param sellOrders how many distinct sell offers make up the book
     */
    public record BzVolume(long buyWeek, long sellWeek, int buyOrders, int sellOrders) {

        /** Units sold per hour on average, the figure an hourly output is worth comparing against. */
        public double sellPerHour() {
            return sellWeek / (7.0 * 24.0);
        }
    }

    private ScheduledExecutorService scheduler;

    private volatile Map<String, BzPrice> prices = Map.of();
    private volatile Map<String, BzVolume> volumes = Map.of();

    /** When something last asked this cache a question – the whole of the refresh condition. */
    private volatile long lastReadAt;

    /** When a read against an empty map last asked for an out-of-band refresh. */
    private volatile long lastColdKickAt;

    /** Whether the map has ever held anything, so the "it is ready" line is printed once. */
    private volatile boolean populated;

    private BazaarPriceCache() {
    }

    public static BazaarPriceCache getInstance() {
        return INSTANCE;
    }

    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "SBS-BazaarPrice-Sync");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::refreshSafely, 10, PERIOD_SECONDS, TimeUnit.SECONDS);
        // Update wave: adopt any snapshot the order sync pulls (every ~30s) instead of serving prices
        // from our own copy until this cache's slower 60s tick comes round.
        BazaarSnapshot.getInstance().addListener(this::rebuild);
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][BazaarPrice] Refresh scheduled every {}s (while prices are being read, + update wave).",
                PERIOD_SECONDS);
    }

    /**
     * Both quick_status prices for a Bazaar product id, or {@code null} if unknown.
     *
     * <p>Like every read here it records that somebody wanted a price, which is what keeps the
     * background refresh running - see the class comment.
     */
    public BzPrice get(String skyblockId) {
        Map<String, BzPrice> snapshot = demand();
        return skyblockId == null ? null : snapshot.get(skyblockId);
    }

    /** Instant-buy price for a Bazaar product id, or {@code null} if unknown. */
    public Long getBuy(String skyblockId) {
        BzPrice price = get(skyblockId);
        return price != null ? price.buy() : null;
    }

    /** Traded volume for a Bazaar product id, or {@code null} if unknown. */
    public BzVolume volume(String skyblockId) {
        markRead();
        return skyblockId == null ? null : volumes.get(skyblockId);
    }

    /**
     * The product key this cache would actually answer for an id, or {@code null} when it has none.
     *
     * <p>The id's own spelling wins. Failing that, the other attribute-shard spelling is tried:
     * {@code SHARD_<NAME>} is how the Bazaar keys the 320 attribute shards while a name-derived id
     * comes out {@code <NAME>_SHARD}, so without this an id that could only be read off a display
     * name never reached its product. (The older {@code PRISMARINE_SHARD}-style items are keyed the
     * other way round and hit on the first test, so the flip never sees them.)
     *
     * <p>Exposed as the key rather than only the price because a diagnostic has to be able to print
     * <i>which</i> key was asked for - "no price" and "asked the wrong question" look identical from
     * the outside, and telling them apart is the whole job of {@code core/dev/PriceProbe}.
     */
    public String keyFor(String skyblockId) {
        if (skyblockId == null || skyblockId.isBlank()) {
            return null;
        }
        Map<String, BzPrice> snapshot = demand();
        String upper = skyblockId.toUpperCase(Locale.ROOT);
        if (snapshot.containsKey(upper)) {
            return upper;
        }
        String alias = ItemPriceKey.shardAlias(upper);
        return alias != null && snapshot.containsKey(alias) ? alias : null;
    }

    /** Prices for an id under {@link #keyFor}'s rule, or {@code null} when nothing matches. */
    public BzPrice priceOf(String skyblockId) {
        return get(keyFor(skyblockId));
    }

    /** Traded volume for an id under {@link #keyFor}'s rule, or {@code null} when nothing matches. */
    public BzVolume volumeOf(String skyblockId) {
        return volume(keyFor(skyblockId));
    }

    /** Whether the product map holds anything at all – i.e. whether a miss means anything. */
    public boolean ready() {
        return !prices.isEmpty();
    }

    /** How many products the last snapshot carried; {@code 0} before the first pull. */
    public int productCount() {
        return prices.size();
    }

    /**
     * Every product id the last snapshot carried, empty before the first pull.
     *
     * <p>Exposed because the product map is the only keyless source that enumerates a whole category
     * of items - the Missing Shards list builds its catalogue of all 320 {@code SHARD_*} ids from it,
     * since no Hypixel resource lists them. The map is immutable and swapped wholesale, so this is a
     * lock-free read of a consistent set.
     */
    public java.util.Set<String> ids() {
        return demand().keySet();
    }

    /** How stale the underlying snapshot is, so a view can date the prices it is showing. */
    public long priceAgeMs() {
        return BazaarSnapshot.getInstance().ageMs();
    }

    /** Kicks off a refresh right now (used when the "Show Lowest Bazaar Price" toggle turns on). */
    public synchronized void requestRefresh() {
        if (scheduler != null) {
            scheduler.execute(this::refreshSafely);
        }
    }

    /**
     * Records that something read the map, and hands back the snapshot it read.
     *
     * <p>A read against an empty map also asks for a refresh straight away rather than leaving the
     * caller to wait out the period. The snapshot is taken once and returned, so a caller cannot see
     * the map swapped between the emptiness test and its own lookup.
     */
    private Map<String, BzPrice> demand() {
        Map<String, BzPrice> snapshot = prices;
        markRead();
        return snapshot;
    }

    /** The bookkeeping half of {@link #demand}, for the reads that do not want the price map. */
    private void markRead() {
        lastReadAt = System.currentTimeMillis();
        if (prices.isEmpty()) {
            kickColdStart();
        }
    }

    /** Asks for an out-of-band refresh after a read found nothing, at most once per interval. */
    private void kickColdStart() {
        long now = System.currentTimeMillis();
        if (now - lastColdKickAt < COLD_KICK_MS) {
            return;
        }
        lastColdKickAt = now;
        if (!populated) {
            // Once, and at info: an empty map is indistinguishable from a broken feature from the
            // player's side, and this is the line that tells the two apart in a shared log.
            SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][BazaarPrice] A price was asked for before the first snapshot arrived;"
                            + " requesting one now.");
        }
        requestRefresh();
    }

    /**
     * Whether anything wants Bazaar prices right now.
     *
     * <p>Two ways to answer yes, and the second is the one that matters. A handful of settings mean
     * "keep this warm whether or not I have looked yet", so they pre-warm. Everything else - and that
     * is most of the mod - simply reads the map, and a recent read is itself the evidence that the
     * data is wanted. See the class comment for why this is not a list of features.
     */
    private boolean wanted() {
        var cfg = ConfigManager.getInstance().get();
        if (cfg.itemOverlay.showBazaarPrice
                || cfg.gemstoneProfit.enabled
                || cfg.nucleusRun.enabled
                || cfg.hunting.missingShards
                || sbs.modid.client.economy.minions.logic.MinionCalcService.wantsAhPrices()) {
            return true;
        }
        return System.currentTimeMillis() - lastReadAt < DEMAND_WINDOW_MS;
    }

    private void refreshSafely() {
        try {
            if (!wanted()) {
                return;     // nothing has read a price and no setting pre-warms - don't hit the API
            }
            if (Minecraft.getInstance().player == null) {
                return;
            }
            refresh();
        } catch (Throwable t) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][BazaarPrice] Refresh cycle failed", t);
        }
    }

    private void refresh() {
        // Shared store: a pull the 5s order sync just made is reused here instead of a second
        // full-Bazaar request. When only this cache is active it drives the 60s pull itself.
        BazaarSnapshot snapshot = BazaarSnapshot.getInstance();
        long generationBefore = snapshot.generation();
        BazaarApiClient.Response response = snapshot.get(PERIOD_SECONDS * 1000L);
        if (response == null) {
            return;
        }
        if (snapshot.generation() != generationBefore) {
            return; // this call did the fetch - the update wave already rebuilt the map
        }
        rebuild(response);
    }

    /** Rebuilds the id -> price map from a snapshot; the update wave's entry point. */
    private void rebuild(BazaarApiClient.Response response) {
        Map<String, BzPrice> map = new HashMap<>();
        Map<String, BzVolume> volumeMap = new HashMap<>();
        for (Map.Entry<String, BazaarApiClient.Product> entry : response.products.entrySet()) {
            BazaarApiClient.Product product = entry.getValue();
            if (product == null || product.quick_status == null) {
                continue;
            }
            // Straight from products -> <id> -> quick_status: both ready-made doubles.
            double buyPrice = product.quick_status.buyPrice;
            double sellPrice = product.quick_status.sellPrice;
            if (buyPrice > 0 || sellPrice > 0) {
                map.put(entry.getKey(), new BzPrice(Math.round(buyPrice), Math.round(sellPrice)));
            }
            // Kept unconditionally on the price test above: a product can legitimately have moved
            // volume this week and no live book right now, and that combination is precisely the one
            // a "can I actually sell this" check wants to see rather than silently miss.
            volumeMap.put(entry.getKey(), new BzVolume(
                    product.quick_status.buyMovingWeek, product.quick_status.sellMovingWeek,
                    product.quick_status.buyOrders, product.quick_status.sellOrders));
        }
        this.prices = Map.copyOf(map);
        this.volumes = Map.copyOf(volumeMap);

        // Once, when the map first fills. Two numbers rather than one: the shard count is the one
        // that separates "the Bazaar answered" from "the Bazaar answered and it still trades the
        // things the hunting features are built on", which is otherwise only knowable in game.
        if (!populated && !prices.isEmpty()) {
            populated = true;
            int shards = 0;
            for (String id : prices.keySet()) {
                if (ItemPriceKey.isShard(id)) {
                    shards++;
                }
            }
            SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][BazaarPrice] Product map ready: {} products, {} of them shards.",
                    prices.size(), shards);
        }
    }
}
