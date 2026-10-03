/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.pricehistory.logic;

import com.google.gson.Gson;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.licence.privacy.ConsentScope;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;

/**
 * Client for the SkyBlock Simplified price API ({@code https://skyblocksimplified.info}) – the
 * same backend the website reads, so the in-game charts show exactly what the site shows.
 *
 * <p>Auth is the licence token from the Licence Token module ({@code Authorization: Bearer}), read
 * fresh from the config on every request – never hardcoded. Responses are requested gzipped
 * ({@code Accept-Encoding: gzip}) and inflated manually, since the JDK {@link HttpClient} does not
 * decompress transparently. All requests run on one background daemon thread; results are handed to
 * the callback on that thread (callers store them in volatile fields the render loop polls).
 */
public final class PriceApi {

    /** Primary endpoint: everything over HTTPS on 443 (tokens travel as headers, never in URLs). */
    private static final String BASE_HTTPS = "https://skyblocksimplified.info";

    /** Legacy direct-port endpoint, used automatically while 443 is not serving yet. */
    private static final String BASE_LEGACY = "http://skyblocksimplified.info:3000";

    private static final Duration TIMEOUT = Duration.ofSeconds(12);

    // Must come AFTER the constants above: static initializers run in declaration order, and the
    // constructor reads TIMEOUT (a Duration is not a compile-time constant – it would still be null).
    private static final PriceApi INSTANCE = new PriceApi();

    /** The base that actually answered ({@code null} until the first request / probe settles). */
    private volatile String activeBase;
    private volatile boolean probeStarted;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final Gson gson = new Gson();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SBS-PriceHistory");
        thread.setDaemon(true);
        return thread;
    });

    /** Result callback: exactly one of {@code result} / {@code error} is non-null. */
    public interface Callback<T> {
        void done(T result, String error);
    }

    private PriceApi() {
    }

    public static PriceApi getInstance() {
        return INSTANCE;
    }

    /**
     * The backend base other API clients (e.g. the appraise client) should try first: the
     * endpoint this session already settled on, or the HTTPS primary while unsettled. Callers
     * retry {@link #legacyBase()} themselves when the connection fails outright.
     */
    public static String base() {
        String active = INSTANCE.activeBase;
        return active != null ? active : BASE_HTTPS;
    }

    /** The legacy direct-port endpoint, for the connect-failure fallback. */
    public static String legacyBase() {
        return BASE_LEGACY;
    }

    /** True when a licence token is configured (Licence Token module). */
    public static boolean hasToken() {
        return sbs.modid.client.core.config.LicenceToken.getInstance().isSet();
    }

    /** Fetches one item's price data, e.g. {@code fetchItem("JUDGEMENT_CORE", "7d", cb)}. */
    public void fetchItem(String itemId, String range, Callback<ItemData> callback) {
        executor.execute(() -> {
            try {
                String json = get("/api/item/" + itemId + "?range=" + range);
                ItemData data = gson.fromJson(json, ItemData.class);
                if (data == null || data.t == null) {
                    callback.done(null, "Empty response");
                    return;
                }
                callback.done(data, null);
            } catch (ApiException e) {
                callback.done(null, e.getMessage());
            } catch (Exception e) {
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][PriceHistory] /api/item/{} failed: {}", itemId, e.toString());
                callback.done(null, "Connection failed");
            }
        });
    }

    /** Fetches the full item catalogue: rows of {@code ["ITEM_ID", "bz"|"ah"]}. */
    public void fetchItems(Callback<String[][]> callback) {
        executor.execute(() -> {
            try {
                String json = get("/api/items");
                ItemsResponse response = gson.fromJson(json, ItemsResponse.class);
                if (response == null || response.items == null) {
                    callback.done(null, "Empty response");
                    return;
                }
                callback.done(response.items, null);
            } catch (ApiException e) {
                callback.done(null, e.getMessage());
            } catch (Exception e) {
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][PriceHistory] /api/items failed: {}", e.toString());
                callback.done(null, "Connection failed");
            }
        });
    }

    /**
     * An authenticated, gzip-aware GET against the SBS API, for other modules.
     *
     * <p>Exposed so features like the Quest Guide reuse this one client rather than re-implementing
     * the licence-token headers, the gzip handling and the endpoint fallback – three things that are
     * easy to get subtly wrong and would then be wrong in two places.
     *
     * <p><b>Blocking</b> – callers must run it off the render thread.
     */
    public String getAuthenticated(String path) throws Exception {
        return get(path);
    }

    /**
     * Blocking GET with automatic endpoint selection: HTTPS is the primary endpoint; while 443 is
     * not serving yet, the request transparently falls back to the legacy direct-port endpoint and
     * the choice sticks for the session (an {@link ApiException} means the server ANSWERED – that
     * endpoint is up, only the request itself failed).
     */
// ============================================================================
// [MODERATOR AUDIT / NETWORK DISCLOSURE]
// KEYWORDS: NETWORK_OUTBOUND, NETWORK_INBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
// ENDPOINT: https://skyblocksimplified.info/api/{item/<id>,items,health}
// METHOD: GET
// PURPOSE: Price history for one item, the list of items that have history, and a health
//   probe used to decide whether the backend is reachable at all.
// DATA SENT: The item id and a time range in the path and query string, plus the
//   licence-token headers. No body.
// DATA RECEIVED: Read-only JSON - dated price points. Parsed with Gson.
// SAFETY DECLARATION: Sends the licence token (Authorization: Bearer, X-Api-Token),
//   which identifies the PURCHASE and not the player. No Mojang credentials, no
//   session id, no OS or hardware telemetry. Refused until the user grants
//   ConsentScope.LICENCE_VALIDATION in Licence Token > Privacy and data; every switch there
//   starts off. See PRIVACY.md.
// ============================================================================
    private String get(String path) throws Exception {
        String base = activeBase;
        if (base != null) {
            return getFrom(base, path);
        }
        try {
            String body = getFrom(BASE_HTTPS, path);
            activeBase = BASE_HTTPS;
            return body;
        } catch (ApiException answered) {
            activeBase = BASE_HTTPS; // 401/404/5xx = HTTPS is live, the request itself failed
            throw answered;
        } catch (Exception unreachable) {
            try {
                String body = getFrom(BASE_LEGACY, path);
                activeBase = BASE_LEGACY;
                SkyblockSimplifiedSBS.LOGGER.info(
                        "[SBS][PriceHistory] HTTPS endpoint not reachable yet - using the legacy endpoint.");
                return body;
            } catch (ApiException answeredLegacy) {
                activeBase = BASE_LEGACY;
                SkyblockSimplifiedSBS.LOGGER.info(
                        "[SBS][PriceHistory] HTTPS endpoint not reachable yet - using the legacy endpoint.");
                throw answeredLegacy;
            }
        }
    }

    /** Blocking GET against one base, translating API errors into readable messages. */
    private String getFrom(String base, String path) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(TIMEOUT)
                .header("Accept-Encoding", "gzip")
                .GET();
        String token = sbs.modid.client.core.config.LicenceToken.getInstance().get();
        if (!token.isBlank()) {
            // Both accepted header forms are sent; X-Api-Token is the one the API spec mandates.
            builder.header("Authorization", "Bearer " + token.trim());
            builder.header("X-Api-Token", token.trim());
        }
        HttpResponse<byte[]> response = sbs.modid.client.core.api.SbsApi.send(
                ConsentScope.LICENCE_VALIDATION, http, builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        String body = inflate(response);
        int status = response.statusCode();
        if (status == 200) {
            return body;
        }
        String apiError = errorCode(body);
        if (status == 401 || "invalid_token".equals(apiError)) {
            throw new ApiException("Invalid licence token");
        }
        if (status == 404 || "unknown_item".equals(apiError)) {
            throw new ApiException("unknown_item");
        }
        throw new ApiException("HTTP " + status);
    }

    /**
     * The website origin matching the reachable endpoint: HTTPS by default / once 443 serves,
     * the plain-HTTP origin while only the legacy setup is up. Browser URLs are built from this.
     */
    public String siteBase() {
        return BASE_LEGACY.equals(activeBase)
                ? "http://skyblocksimplified.info/"
                : "https://skyblocksimplified.info/";
    }

    /**
     * Fires the async endpoint probe once (the health check needs no token) so {@link #siteBase()}
     * is settled by the time the embedded browser finishes starting up.
     */
    public void probe() {
        if (activeBase != null || probeStarted) {
            return;
        }
        probeStarted = true;
        executor.execute(() -> {
            try {
                get("/api/health");
            } catch (Exception ignored) {
                // Unreachable either way – requests will keep trying and settle later.
            }
        });
    }

    /** Inflates a gzip body (by header or magic bytes); small responses arrive uncompressed. */
    private static String inflate(HttpResponse<byte[]> response) throws Exception {
        byte[] raw = response.body();
        boolean gzip = response.headers().firstValue("Content-Encoding")
                .map(value -> value.toLowerCase(java.util.Locale.ROOT).contains("gzip")).orElse(false)
                || (raw.length > 2 && (raw[0] & 0xFF) == 0x1F && (raw[1] & 0xFF) == 0x8B);
        if (!gzip) {
            return new String(raw, StandardCharsets.UTF_8);
        }
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(raw))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The {@code {"error":"..."}} code of a failed response, or {@code null}. */
    private String errorCode(String body) {
        try {
            ErrorResponse error = gson.fromJson(body, ErrorResponse.class);
            return error != null ? error.error : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** A readable, user-facing API failure. */
    private static final class ApiException extends Exception {
        ApiException(String message) {
            super(message);
        }
    }

    private static final class ErrorResponse {
        String error;
    }

    private static final class ItemsResponse {
        String[][] items;
    }

    /**
     * One item's price data. Field names mirror the API JSON exactly.
     *
     * <p>Bazaar ({@code t = "bz"}): {@code h} rows are
     * {@code [ts, bucketSec, buyAvg, sellAvg, buyMin, buyMax, sellMin, sellMax]}, {@code s} rows are
     * {@code [ts, instabuy, instasell, buyMovingWeek, sellMovingWeek]}.
     *
     * <p>Auction House ({@code t = "ah"}): {@code h} rows are
     * {@code [ts, bucketSec, avg, median, min, max, sales, itemCount]}, {@code s} rows are the last
     * 24h of sales {@code [ts, totalPrice, amount, bin]} (newest first), {@code lb} is
     * {@code [lowestBinUnitPrice, activeOffers]} or null. Prices are unit prices, timestamps unix
     * seconds (UTC).
     */
    public static final class ItemData {
        public String t;
        public String i;
        public long g;
        public double[][] h;
        public double[][] s;
        public double[] lb;

        public boolean isBazaar() {
            return "bz".equals(t);
        }
    }
}
