/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.prices;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.async.SbsExecutors;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.core.perf.Headroom;
import sbs.modid.client.core.perf.Perf;
import sbs.modid.client.core.tick.Island;
import sbs.modid.client.core.tick.TickContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The last price each item was seen at, kept across sessions, for when the live markets have none.
 *
 * <p>A rare auction item is listed for a day every few weeks; between listings the lowest-BIN map
 * does not contain it and every total it is part of would value it at nothing. This store remembers
 * what it last went for. {@link LbinCache#getLbin} and {@link BazaarPriceCache#get} consult it after
 * their live maps miss, which is how every local price consumer gets the fallback without knowing
 * about it.
 *
 * <p><b>Reads</b> are one {@link ConcurrentHashMap} lookup returning a pre-boxed value, safe from any
 * thread, never deferred. <b>Writes</b> are not done by the threads that observe prices: those only
 * put the observation into a bounded queue (latest per key). A {@code BACKGROUND} tick task merges
 * the queue in slices while SBS is under its tick budget, and a second one saves in a calm moment.
 *
 * <p>The outlier rule lives in {@link #offer} and is described there.
 */
public final class BackupPrices {

    /** Where a sample came from. The JSON name is what the file stores. */
    public enum Source {
        BAZAAR_SELL("bazaar_sell"), BAZAAR_BUY("bazaar_buy"), AH_LBIN("ah_lbin"), AH_SOLD("ah_sold"),
        NPC("npc"), API("api");

        final String json;

        Source(String json) {
            this.json = json;
        }

        static Source fromJson(String name) {
            for (Source source : values()) {
                if (source.json.equals(name)) {
                    return source;
                }
            }
            return null;
        }
    }

    /**
     * One key's history: the last {@link #WINDOW} samples (oldest first) and the value published
     * from them. Immutable; a merge replaces the whole object.
     *
     * @param samples  the window, oldest first, never empty
     * @param value    the published (median) value
     * @param seenAt   when the newest sample was taken, epoch millis
     * @param count    samples taken over the key's whole life
     * @param source   where the window's samples came from
     * @param boxed    {@code value}, boxed once so a read returns it without allocating
     */
    public record Series(long[] samples, long value, long seenAt, int count, Source source, Long boxed) {

        static Series of(long[] samples, long value, long seenAt, int count, Source source) {
            return new Series(samples, value, seenAt, count, source, value);
        }
    }

    /** One pending observation. */
    record Observation(long price, Source source, long at) {
    }

    /** A Bazaar fallback answer, remembered against the live record it was built for. */
    private record Merged(BazaarPriceCache.BzPrice live, Series buy, Series sell, BazaarPriceCache.BzPrice merged) {
    }

    /** Samples kept per key. Odd, so a full window has a true median. */
    static final int WINDOW = 5;
    /** At most one sample per key in this interval, so the window spans time and not one crawl. */
    static final long SAMPLE_INTERVAL_MS = 10 * 60_000L;
    /** How long a sold window keeps asking prices (LBIN, API) out. */
    static final long SOLD_PREFERENCE_MS = 7 * 24 * 3_600_000L;
    /** Upper bound on keys per market; the least recently seen go first past it. */
    static final int MAX_ENTRIES = 40_000;
    /** Upper bound on queued keys across all markets. */
    static final int MAX_PENDING = 32_000;
    /** Keys merged per tick at full headroom; the slice is this times {@link Headroom#fraction}. */
    static final int MAX_SLICE = 400;
    /** Smallest slice that is worth a pass. */
    static final int MIN_SLICE = 8;
    /** Minimum time between two debounced saves. */
    static final long SAVE_INTERVAL_MS = 60_000L;

    private static final BackupPrices INSTANCE = new BackupPrices();

    private final Map<String, Series> ah = new ConcurrentHashMap<>();
    private final Map<String, Series> bzBuy = new ConcurrentHashMap<>();
    private final Map<String, Series> bzSell = new ConcurrentHashMap<>();
    private final Map<String, Merged> bzMemo = new ConcurrentHashMap<>();

    private final Map<String, Observation> pendingAh = new ConcurrentHashMap<>();
    private final Map<String, Observation> pendingBuy = new ConcurrentHashMap<>();
    private final Map<String, Observation> pendingSell = new ConcurrentHashMap<>();
    private final AtomicInteger pendingSize = new AtomicInteger();

    private final Object saveLock = new Object();

    private volatile boolean loaded;
    private volatile boolean dirty;
    private long lastSaveAt;

    /** Auction id to price key, rebuilt when the live index is republished. Flip thread only. */
    private Map<String, String> auctionKeys = Map.of();
    private long auctionKeysFor = -1;

    // Perf figures. Client thread only, except the overflow counter.
    private long processedTotal;
    private long deferredTotal;
    private long rateSecond;
    private long processedThisSecond;
    private long deferredThisSecond;
    private long processedPerSec;
    private long deferredPerSec;
    private final java.util.concurrent.atomic.AtomicLong overflow = new java.util.concurrent.atomic.AtomicLong();
    private int lastQueueSample = -1;
    private int growingSamples;
    private long lastGrowthSampleAt;
    private boolean growthWarned;

    private BackupPrices() {
    }

    public static BackupPrices getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.ItemOverlaySettings cfg() {
        return ConfigManager.getInstance().get().itemOverlay;
    }

    private static boolean enabled() {
        return cfg().backupPrices;
    }

    // ------------------------------------------------------------------ lifecycle

    /** Loads the file once, on the io thread; until then every read answers "no backup". */
    public void start() {
        SbsExecutors.io().execute(this::load);
        Thread hook = new Thread(this::saveOnShutdown, "SBS-BackupPrices-Shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
    }

    /** World change: save what is dirty, regardless of load. */
    public void onWorldChange() {
        if (loaded && dirty) {
            scheduleSave();
        }
    }

    // ------------------------------------------------------------------ reads (any thread)

    /** The backup lowest-BIN for a key, or {@code null}. No allocation. */
    public Long ahFallback(String key) {
        if (key == null || !loaded || !enabled()) {
            return null;
        }
        Series series = ah.get(key);
        return series != null && fresh(series) ? series.boxed() : null;
    }

    /**
     * The Bazaar answer with backups filled in: {@code live} as it is when both sides are priced,
     * otherwise each 0 or missing side from the backup. {@code null} when neither has anything.
     * Allocates only the first time a given live record needs patching.
     */
    public BazaarPriceCache.BzPrice bazaarFallback(String key, BazaarPriceCache.BzPrice live) {
        if (key == null || !loaded || !enabled()) {
            return live;
        }
        Series buy = live != null && live.buy() > 0 ? null : bzBuy.get(key);
        Series sell = live != null && live.sell() > 0 ? null : bzSell.get(key);
        if (buy != null && !fresh(buy)) {
            buy = null;
        }
        if (sell != null && !fresh(sell)) {
            sell = null;
        }
        if (buy == null && sell == null) {
            return live;
        }
        Merged memo = bzMemo.get(key);
        if (memo != null && memo.live() == live && memo.buy() == buy && memo.sell() == sell) {
            return memo.merged();
        }
        BazaarPriceCache.BzPrice merged = new BazaarPriceCache.BzPrice(
                buy != null ? buy.value() : live == null ? 0 : live.buy(),
                sell != null ? sell.value() : live == null ? 0 : live.sell());
        bzMemo.put(key, new Merged(live, buy, sell, merged));
        return merged;
    }

    /** The AH series for a key (for markers and the debug command), or {@code null}. */
    public Series ahSeries(String key) {
        return key == null || !loaded ? null : ah.get(key);
    }

    /** The Bazaar series for a key and side, or {@code null}. */
    public Series bazaarSeries(String key, boolean buySide) {
        if (key == null || !loaded) {
            return null;
        }
        return (buySide ? bzBuy : bzSell).get(key);
    }

    public int size() {
        return ah.size() + bzBuy.size() + bzSell.size();
    }

    private static boolean fresh(Series series) {
        int days = cfg().backupPriceMaxAgeDays;
        return days <= 0 || System.currentTimeMillis() - series.seenAt() <= days * 86_400_000L;
    }

    // ------------------------------------------------------------------ observations (any thread)

    /** A finished lowest-BIN crawl. Called on the crawl thread. */
    public void observeLbin(Map<String, Long> lbin) {
        if (!enabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> entry : lbin.entrySet()) {
            enqueue(pendingAh, entry.getKey(), entry.getValue(), Source.AH_LBIN, now);
        }
    }

    /** A Bazaar snapshot. Called on the Bazaar thread. */
    public void observeBazaar(Map<String, BazaarPriceCache.BzPrice> prices) {
        if (!enabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<String, BazaarPriceCache.BzPrice> entry : prices.entrySet()) {
            BazaarPriceCache.BzPrice price = entry.getValue();
            if (price.buy() > 0) {
                enqueue(pendingBuy, entry.getKey(), price.buy(), Source.BAZAAR_BUY, now);
            }
            if (price.sell() > 0) {
                enqueue(pendingSell, entry.getKey(), price.sell(), Source.BAZAAR_SELL, now);
            }
        }
    }

    /** One price-API "now" figure for an AH item id. Called on the price API thread. */
    public void observeApi(String key, long price) {
        if (enabled() && key != null) {
            enqueue(pendingAh, key, price, Source.API, System.currentTimeMillis());
        }
    }

    /**
     * Auctions that just ended. A BIN auction whose id is one of the live index's cheapest listings
     * sold at that price, so it becomes an {@code ah_sold} sample for that listing's key. Called on
     * the flip watcher thread.
     */
    public void observeEnded(List<String> auctionIds, List<Long> prices) {
        if (!enabled() || auctionIds.isEmpty()) {
            return;
        }
        LiveAuctionIndex index = LiveAuctionIndex.getInstance();
        if (auctionKeysFor != index.updatedAtMs()) {
            Map<String, String> keys = new java.util.HashMap<>();
            for (Map.Entry<String, LiveAuctionIndex.Entry> entry : index.snapshot().entrySet()) {
                for (LiveAuctionIndex.Listing listing : entry.getValue().cheapest()) {
                    keys.put(listing.auctionId(), entry.getKey());
                }
            }
            auctionKeys = keys;
            auctionKeysFor = index.updatedAtMs();
        }
        long now = System.currentTimeMillis();
        for (int i = 0; i < auctionIds.size(); i++) {
            String id = auctionIds.get(i);
            String key = id == null ? null : auctionKeys.get(id.toLowerCase(Locale.ROOT).replace("-", ""));
            if (key != null) {
                enqueue(pendingAh, key, prices.get(i), Source.AH_SOLD, now);
            }
        }
    }

    private void enqueue(Map<String, Observation> queue, String key, Long price, Source source, long now) {
        if (key == null || price == null || price <= 0) {
            return;
        }
        Observation observation = new Observation(price, source, now);
        if (queue.replace(key, observation) != null) {
            return; // newest observation per key wins; the queue does not grow
        }
        if (pendingSize.get() >= MAX_PENDING) {
            overflow.incrementAndGet();
            return;
        }
        if (queue.putIfAbsent(key, observation) == null) {
            pendingSize.incrementAndGet();
        } else {
            queue.replace(key, observation);
        }
    }

    /** Queued keys across all markets. */
    public int queueLength() {
        return pendingSize.get();
    }

    // ------------------------------------------------------------------ merge (client thread)

    /** {@code tick.price.backup.update}: merges a slice of the queue while SBS is under budget. */
    public void onUpdateTick() {
        rollRates();
        if (!enabled()) {
            if (pendingSize.get() > 0) {
                pendingAh.clear();
                pendingBuy.clear();
                pendingSell.clear();
                pendingSize.set(0);
            }
            return;
        }
        if (pendingSize.get() == 0 || !loaded) {
            return;
        }
        int budget = slice(Headroom.fraction());
        if (budget == 0) {
            deferredTotal++;
            deferredThisSecond++;
            return;
        }
        drainSlice(budget);
    }

    /** Merges up to {@code budget} queued keys, AH first. Client thread (or a test). */
    void drainSlice(int budget) {
        long now = System.currentTimeMillis();
        budget = drain(pendingAh, ah, budget, now);
        budget = drain(pendingBuy, bzBuy, budget, now);
        drain(pendingSell, bzSell, budget, now);
    }

    /** Keys to merge this tick for a headroom fraction: 0 when there is none, else at least {@link #MIN_SLICE}. */
    static int slice(double headroom) {
        if (headroom <= 0) {
            return 0;
        }
        return Math.max(MIN_SLICE, (int) Math.round(MAX_SLICE * Math.min(1, headroom)));
    }

    private int drain(Map<String, Observation> queue, Map<String, Series> store, int budget, long now) {
        if (budget <= 0 || queue.isEmpty()) {
            return budget;
        }
        Iterator<Map.Entry<String, Observation>> it = queue.entrySet().iterator();
        while (budget > 0 && it.hasNext()) {
            Map.Entry<String, Observation> entry = it.next();
            String key = entry.getKey();
            Observation observation = entry.getValue();
            // Conditional remove: an observation that arrived after this one was read stays queued.
            if (!queue.remove(key, observation)) {
                continue;
            }
            pendingSize.decrementAndGet();
            budget--;
            processedTotal++;
            processedThisSecond++;
            Series before = store.get(key);
            Series after = offer(before, observation.price(), observation.source(), observation.at());
            if (after != before) {
                store.put(key, after);
                dirty = true;
            }
        }
        return budget;
    }

    /**
     * The outlier rule. Pure, so it is unit-tested on its own.
     *
     * <ul>
     *   <li>A price of 0 or less changes nothing.</li>
     *   <li>No history: the sample is the value.</li>
     *   <li>Sold beats asking: an {@code ah_sold} sample restarts a window built from asking prices;
     *       an asking price ({@code ah_lbin}, {@code api}) is ignored while the sold window is
     *       younger than {@link #SOLD_PREFERENCE_MS}, and restarts the window once it is older.</li>
     *   <li>At most one sample per key per {@link #SAMPLE_INTERVAL_MS}.</li>
     *   <li>The window keeps the last {@link #WINDOW} samples; the value is their median, and with an
     *       even count the middle sample closer to the previous value. One wild sample can therefore
     *       never become the value once any history exists - it needs a majority of the window.</li>
     * </ul>
     *
     * @return {@code previous} itself when nothing changed, otherwise the new series
     */
    static Series offer(Series previous, long price, Source source, long now) {
        if (price <= 0) {
            return previous;
        }
        if (previous == null) {
            return Series.of(new long[] {price}, price, now, 1, source);
        }
        boolean wasSold = previous.source() == Source.AH_SOLD;
        boolean isSold = source == Source.AH_SOLD;
        boolean restart = false;
        if (wasSold && !isSold) {
            if (now - previous.seenAt() < SOLD_PREFERENCE_MS) {
                return previous;
            }
            restart = true;
        } else if (isSold && !wasSold) {
            restart = true;
        }
        if (restart) {
            return Series.of(new long[] {price}, price, now, previous.count() + 1, source);
        }
        if (now - previous.seenAt() < SAMPLE_INTERVAL_MS) {
            return previous;
        }
        long[] old = previous.samples();
        int keep = Math.min(old.length, WINDOW - 1);
        long[] samples = new long[keep + 1];
        System.arraycopy(old, old.length - keep, samples, 0, keep);
        samples[keep] = price;
        return Series.of(samples, median(samples, previous.value()), now, previous.count() + 1, source);
    }

    /** The median; with an even count, the middle value closer to {@code previous}. */
    static long median(long[] samples, long previous) {
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        if ((n & 1) == 1) {
            return sorted[n / 2];
        }
        long low = sorted[n / 2 - 1];
        long high = sorted[n / 2];
        return Math.abs(low - previous) <= Math.abs(high - previous) ? low : high;
    }

    // ------------------------------------------------------------------ save

    /**
     * {@code tick.price.backup.save}: the gate of the calm moment. Registered with a gate that
     * already excludes menus and fights; the budget check and the debounce are here.
     */
    public void onSaveTick() {
        if (!dirty || !loaded || Headroom.fraction() <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastSaveAt < SAVE_INTERVAL_MS) {
            return;
        }
        lastSaveAt = now;
        scheduleSave();
    }

    /** No menu, not in the Catacombs, not in Kuudra's Hollow. */
    public static boolean calm(TickContext context) {
        return context.screen() == TickContext.ScreenKind.NONE && !context.inCatacombs()
                && !context.on(Island.KUUDRAS_HOLLOW);
    }

    private void scheduleSave() {
        dirty = false;
        SbsExecutors.io().execute(this::writeSafely);
    }

    private void saveOnShutdown() {
        if (loaded && dirty) {
            dirty = false;
            writeSafely();
        }
    }

    private void writeSafely() {
        synchronized (saveLock) {
            long started = System.nanoTime();
            try {
                evict(ah);
                evict(bzBuy);
                evict(bzSell);
                write(SBSFiles.backupPricesFile(), toJson());
                Perf.countDiskWrite();
                SkyblockSimplifiedSBS.LOGGER.debug("[SBS][BackupPrices] Saved {} entries in {} ms.",
                        size(), (System.nanoTime() - started) / 1_000_000);
            } catch (IOException | RuntimeException e) {
                dirty = true; // try again at the next calm moment
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][BackupPrices] Save failed: {}", e.toString());
            }
        }
    }

    private static void evict(Map<String, Series> store) {
        int excess = store.size() - MAX_ENTRIES;
        if (excess <= 0) {
            return;
        }
        List<Map.Entry<String, Series>> entries = new ArrayList<>(store.entrySet());
        entries.sort((a, b) -> Long.compare(a.getValue().seenAt(), b.getValue().seenAt()));
        for (int i = 0; i < excess; i++) {
            store.remove(entries.get(i).getKey());
        }
    }

    private static void write(Path target, String json) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temp, json, StandardCharsets.UTF_8);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // Compact layout: {"v":1,"ah":{"KEY":[source,seenAt,count,value,[samples...]]},"bzBuy":{...},"bzSell":{...}}
    String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("v", 1);
        root.add("ah", section(ah));
        root.add("bzBuy", section(bzBuy));
        root.add("bzSell", section(bzSell));
        return root.toString();
    }

    private static JsonObject section(Map<String, Series> store) {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, Series> entry : store.entrySet()) {
            Series s = entry.getValue();
            JsonArray row = new JsonArray();
            row.add(s.source().json);
            row.add(s.seenAt());
            row.add(s.count());
            row.add(s.value());
            JsonArray samples = new JsonArray();
            for (long sample : s.samples()) {
                samples.add(sample);
            }
            row.add(samples);
            out.add(entry.getKey(), row);
        }
        return out;
    }

    private void load() {
        Path file = SBSFiles.backupPricesFile();
        try {
            if (Files.isRegularFile(file)) {
                fromJson(Files.readString(file, StandardCharsets.UTF_8));
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][BackupPrices] Loaded {} entries.", size());
            }
        } catch (IOException | RuntimeException e) {
            // A corrupt file costs the history, not the session: start empty and overwrite it later.
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][BackupPrices] Could not read {}: {}", file, e.toString());
        } finally {
            loaded = true;
        }
    }

    void fromJson(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        readSection(root.getAsJsonObject("ah"), ah);
        readSection(root.getAsJsonObject("bzBuy"), bzBuy);
        readSection(root.getAsJsonObject("bzSell"), bzSell);
    }

    private static void readSection(JsonObject section, Map<String, Series> store) {
        if (section == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : section.entrySet()) {
            try {
                JsonArray row = entry.getValue().getAsJsonArray();
                Source source = Source.fromJson(row.get(0).getAsString());
                long value = row.get(3).getAsLong();
                JsonArray rawSamples = row.get(4).getAsJsonArray();
                if (source == null || value <= 0 || rawSamples.isEmpty()) {
                    continue;
                }
                int n = Math.min(rawSamples.size(), WINDOW);
                long[] samples = new long[n];
                for (int i = 0; i < n; i++) {
                    samples[i] = rawSamples.get(rawSamples.size() - n + i).getAsLong();
                }
                store.put(entry.getKey(), Series.of(samples, value, row.get(1).getAsLong(),
                        row.get(2).getAsInt(), source));
            } catch (RuntimeException e) {
                // One malformed row is skipped; the rest of the file still loads.
            }
        }
    }

    /** Settings "Clear backup prices": forgets everything and writes the empty file. */
    public void clear() {
        ah.clear();
        bzBuy.clear();
        bzSell.clear();
        bzMemo.clear();
        pendingAh.clear();
        pendingBuy.clear();
        pendingSell.clear();
        pendingSize.set(0);
        if (loaded) {
            scheduleSave();
        }
    }

    // ------------------------------------------------------------------ perf

    private void rollRates() {
        long second = System.currentTimeMillis() / 1000;
        if (second == rateSecond) {
            return;
        }
        processedPerSec = processedThisSecond;
        deferredPerSec = deferredThisSecond;
        processedThisSecond = 0;
        deferredThisSecond = 0;
        rateSecond = second;
        // Growth watch: one sample every 10 s; six rises in a row while on is worth a log line.
        if (second * 1000 - lastGrowthSampleAt >= 10_000) {
            lastGrowthSampleAt = second * 1000;
            int queue = pendingSize.get();
            growingSamples = enabled() && lastQueueSample >= 0 && queue > lastQueueSample ? growingSamples + 1 : 0;
            lastQueueSample = queue;
            if (growingSamples >= 6 && !growthWarned) {
                growthWarned = true;
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][BackupPrices] Queue grew for a minute straight ({} keys, "
                        + "{} deferred so far): price observations arrive faster than the budget lets them merge.",
                        queue, deferredTotal);
            }
        }
    }

    /** The {@code /sbs perf} row. */
    public String perfSummary() {
        return String.format(Locale.ROOT, "%d entries, queue %d, %d processed/s, %d deferred/s, overflow %d, headroom %.2f (%s)%s%s",
                size(), pendingSize.get(), processedPerSec, deferredPerSec, overflow.get(),
                Headroom.fraction(), Headroom.reason(),
                growingSamples >= 6 ? ", GROWING" : "", enabled() ? "" : " (off)");
    }

    /** The debug command's text for one key. */
    public String describe(String key) {
        if (key.isEmpty()) {
            return "Usage: /sbs backupprice <KEY>  (" + perfSummary() + ")";
        }
        long now = System.currentTimeMillis();
        StringBuilder out = new StringBuilder("Backup " + key + ":");
        describe(out, "AH", ah.get(key), now);
        describe(out, "BZ buy", bzBuy.get(key), now);
        describe(out, "BZ sell", bzSell.get(key), now);
        return out.toString();
    }

    private static void describe(StringBuilder out, String label, Series s, long now) {
        out.append("\n  ").append(label).append(": ");
        if (s == null) {
            out.append("none");
            return;
        }
        out.append(s.value()).append(" (").append(s.source().json).append(", seen ")
                .append(BackupPriceMarker.age(s.seenAt(), now)).append(", ").append(s.count())
                .append(" samples, window ").append(Arrays.toString(s.samples())).append(')');
    }

    /** Totals for tests and the debug command. */
    long processedTotal() {
        return processedTotal;
    }

    long deferredTotal() {
        return deferredTotal;
    }

    /** Test seam: marks the store loaded without touching the disk. */
    void markLoadedForTest() {
        loaded = true;
    }

    /** Test seam: empties everything without writing the file. */
    void resetForTest() {
        ah.clear();
        bzBuy.clear();
        bzSell.clear();
        bzMemo.clear();
        pendingAh.clear();
        pendingBuy.clear();
        pendingSell.clear();
        pendingSize.set(0);
        overflow.set(0);
    }
}
