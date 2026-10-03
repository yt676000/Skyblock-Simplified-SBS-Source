/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.bazaar.logic;

import com.google.gson.Gson;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.economy.bazaar.model.BazaarSnapshot;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Thin client for the current Bazaar snapshot, fetched <b>directly from Hypixel</b>
 * ({@code /v2/skyblock/bazaar}). That endpoint needs no API key and is Cloudflare-cached for ~60s, so
 * there is nothing to gain from routing it through the SBS proxy: the client can pull it itself, which
 * keeps this feature working with no backend involvement (same idea as the direct LBIN fetch). The
 * response carries the full order book ({@code buy_summary} / {@code sell_summary}) the order-status
 * notifier needs, which the proxy's slimmed {@code quick_status}-only snapshot did not.
 *
 * <p>Requested gzipped ({@code Accept-Encoding: gzip}, ~440 KB vs ~3.2 MB raw) and inflated manually,
 * since the JDK {@link HttpClient} does not decompress transparently. Network calls are blocking and
 * run on the {@link BazaarSyncService} background thread, never the client thread; a shared TTL cache
 * ({@link BazaarSnapshot}) collapses every consumer into one pull per interval.
 */
public final class BazaarApiClient {

// ============================================================================
// [MODERATOR AUDIT / NETWORK DISCLOSURE]
// KEYWORDS: NETWORK_OUTBOUND, NETWORK_INBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
// ENDPOINT: https://api.hypixel.net/v2/skyblock/bazaar
// METHOD: GET
// PURPOSE: Live bazaar order books - the buy/sell prices every price display, flip list and
//   item valuation in the mod is built from.
// DATA SENT: Nothing in the URL and no body. Accept-Encoding: gzip only (the response is
//   large and is inflated locally, see inflate()).
// DATA RECEIVED: Read-only public JSON - product ids with their order books. Parsed with Gson
//   into a fixed Response record.
// SAFETY DECLARATION: No user credentials, session tokens, Mojang uuids or OS telemetry
//   are collected or transmitted. No licence token is sent - this endpoint is public.
// ============================================================================
    private static final String URL = "https://api.hypixel.net/v2/skyblock/bazaar";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final Gson gson = new Gson();

    /**
     * Fetches and parses the current Bazaar snapshot straight from Hypixel.
     *
     * @return the parsed response, or {@code null} on any network / parse failure or an unsuccessful
     *         response (callers keep the last good snapshot).
     */
    public Response fetch() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(URL))
                    .timeout(TIMEOUT)
                    .header("Accept-Encoding", "gzip")
                    .header("User-Agent", "SkyblockSimplified/1.0")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                return null;
            }
            Response parsed = gson.fromJson(inflate(response), Response.class);
            return (parsed != null && parsed.success && parsed.products != null) ? parsed : null;
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS] Bazaar request failed: {}", e.toString());
            return null;
        }
    }

    /** Inflates a gzip body (by header or magic bytes); an uncompressed body is passed through. */
    private static String inflate(HttpResponse<byte[]> response) throws Exception {
        byte[] raw = response.body();
        boolean gzip = response.headers().firstValue("Content-Encoding")
                .map(value -> value.toLowerCase(Locale.ROOT).contains("gzip")).orElse(false)
                || (raw.length > 2 && (raw[0] & 0xFF) == 0x1F && (raw[1] & 0xFF) == 0x8B);
        if (!gzip) {
            return new String(raw, StandardCharsets.UTF_8);
        }
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(raw))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------
    // Gson models – field names mirror the Hypixel JSON exactly (extra fields are ignored).
    // ------------------------------------------------------------------

    /** Top-level response: {@code { success, lastUpdated, products: { ITEM_ID: Product } } }. */
    public static final class Response {
        public boolean success;
        /**
         * Hypixel's own "this data was generated at" epoch-millis. Distinct from when WE fetched it:
         * the endpoint sits behind a ~60s Cloudflare cache, so a brand-new fetch can still carry
         * minute-old prices. Logged by {@link BazaarSyncService} so a stale-status report can be
         * pinned on the data source instead of the comparison logic.
         */
        public long lastUpdated;
        public Map<String, Product> products;
    }

    /**
     * One product's order book plus its quick status. Field names match the API JSON.
     *
     * <p><b>The two summaries are named inside-out</b> (verified 2026-07-23, and again against a live
     * payload 2026-08-07): they are named for the action <i>you</i> take, not for what they contain.
     * {@code buy_summary} holds the <b>sell offers</b> — the asks you buy from — and {@code
     * sell_summary} holds the <b>buy orders</b> — the bids you sell into. Every reader of this class
     * has to get that right or it compares against the wrong side of the spread; see
     * {@link BazaarSyncService#evaluate} and {@link sbs.modid.client.economy.bazaar.logic.LocalFlipEngine}.
     */
    public static final class Product {
        public List<Summary> sell_summary;
        public List<Summary> buy_summary;
        public QuickStatus quick_status;
    }

    /**
     * One aggregated price level within a summary list. Hypixel returns at most <b>30 levels</b> per
     * side (measured 2026-08-07 across all 2123 products), so a sum over a summary is the top of the
     * book, never the whole of it.
     */
    public static final class Summary {
        public double pricePerUnit;
        public int orders;
        /**
         * Units sitting at this price level. Dropped by this model until 2026-08-07 even though the
         * payload always carried it — it is the only field that says how <i>deep</i> a level is, which
         * is what separates a real market from one order holding up the whole book.
         */
        public long amount;
    }

    /**
     * Hypixel's per-product aggregates. Same inside-out naming as the summaries: {@code buyPrice} is
     * what buying costs you (the weighted average across the sell offers) and {@code sellPrice} is
     * what selling gets you.
     *
     * <p>Everything below the two prices was likewise present in the payload all along and simply not
     * deserialized. The {@code MovingWeek} pair is the only volume signal the client has at all, and
     * without it there is no way to bound a fill rate — see {@link LocalFlipEngine}.
     */
    public static final class QuickStatus {
        public double buyPrice;
        public double sellPrice;

        /** Units currently resting in sell offers / buy orders (standing depth, not throughput). */
        public long buyVolume;
        public long sellVolume;

        /** Units that actually changed hands over the last 7 days — the throughput figure. */
        public long buyMovingWeek;
        public long sellMovingWeek;

        /** How many distinct orders make up each side. A book of two is not a market. */
        public int buyOrders;
        public int sellOrders;
    }
}
