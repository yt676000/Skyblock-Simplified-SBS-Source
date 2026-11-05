/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.pricehistory.logic;

import sbs.modid.client.core.api.ApiFailure;
import com.google.gson.JsonElement;
import sbs.modid.client.core.net.SecureHttp;
import sbs.modid.client.core.net.validation.MessageSchemas;
import sbs.modid.client.core.net.validation.MessageTypes;
import sbs.modid.client.core.net.validation.ValidationException;

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


    private static final Duration TIMEOUT = Duration.ofSeconds(12);

    private static final java.util.regex.Pattern ITEM_ID = java.util.regex.Pattern.compile("^[A-Za-z0-9_:;.+-]{1,96}$");
    private static final java.util.regex.Pattern RANGE = java.util.regex.Pattern.compile("^[0-9a-z]{1,8}$");

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

    /**
     * The fallback base for a connect failure. Every request is https-only, so this is the primary
     * endpoint: a failed connection is retried once against the same TLS origin.
     */
    public static String legacyBase() {
        return BASE_HTTPS;
    }

    /** True when a licence token is configured (Licence Token module). */
    public static boolean hasToken() {
        if (sbs.modid.client.core.net.Credentials.current() != sbs.modid.client.core.net.Credentials.Kind.LICENCE) {
            return false;
        }
        return sbs.modid.client.core.config.LicenceToken.getInstance().isSet();
    }

    /** Fetches one item's price data, e.g. {@code fetchItem("JUDGEMENT_CORE", "7d", cb)}. */
    public void fetchItem(String itemId, String range, Callback<ItemData> callback) {
        executor.execute(() -> {
            try {
                if (!ITEM_ID.matcher(itemId == null ? "" : itemId).matches()
                        || !RANGE.matcher(range == null ? "" : range).matches()) {
                    callback.done(null, "unknown_item");
                    return;
                }
                String json = get("/api/item/" + itemId + "?range=" + range, MessageTypes.PRICE_ITEM);
                ItemData data = MessageSchemas.parse(MessageTypes.PRICE_ITEM, json, ItemData.class);
                if (data == null || data.t == null) {
                    callback.done(null, "Empty response");
                    return;
                }
                callback.done(data, null);
            } catch (ApiException e) {
                callback.done(null, e.getMessage());
            } catch (ValidationException e) {
                callback.done(null, ValidationException.USER_MESSAGE);
            } catch (Exception e) {
                ApiFailure.log("[SBS][PriceHistory]", "/api/item/" + itemId, e);
                callback.done(null, ApiFailure.message(e));
            }
        });
    }

    /** Fetches the full item catalogue: rows of {@code ["ITEM_ID", "bz"|"ah"]}. */
    public void fetchItems(Callback<String[][]> callback) {
        executor.execute(() -> {
            try {
                String json = get("/api/items", MessageTypes.PRICE_ITEMS);
                ItemsResponse response = MessageSchemas.parse(MessageTypes.PRICE_ITEMS, json, ItemsResponse.class);
                if (response == null || response.items == null) {
                    callback.done(null, "Empty response");
                    return;
                }
                callback.done(response.items, null);
            } catch (ApiException e) {
                callback.done(null, e.getMessage());
            } catch (ValidationException e) {
                callback.done(null, ValidationException.USER_MESSAGE);
            } catch (Exception e) {
                ApiFailure.log("[SBS][PriceHistory]", "/api/items", e);
                callback.done(null, ApiFailure.message(e));
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
    public String getAuthenticated(String path, String typeId) throws Exception {
        return get(path, typeId);
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
        return get(path, null);
    }

    private String get(String path, String typeId) throws Exception {
        String body = getFrom(BASE_HTTPS, path, typeId);
        activeBase = BASE_HTTPS;
        return body;
    }

    /** Blocking GET against one base, translating API errors into readable messages. */
    private String getFrom(String base, String path, String typeId) throws Exception {
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
                ConsentScope.LICENCE_VALIDATION, typeId, builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        String body = inflate(response, typeId);
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
        return "https://skyblocksimplified.info/";
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
                get("/api/health", MessageTypes.HEALTH);
            } catch (Exception ignored) {
                // Unreachable either way – requests will keep trying and settle later.
            }
        });
    }

    /** Inflates a gzip body (by header or magic bytes); small responses arrive uncompressed. */
    private static String inflate(HttpResponse<byte[]> response, String typeId) throws Exception {
        byte[] raw = response.body();
        long cap = MessageSchemas.maxBytes(typeId);
        boolean gzip = response.headers().firstValue("Content-Encoding")
                .map(value -> value.toLowerCase(java.util.Locale.ROOT).contains("gzip")).orElse(false)
                || (raw.length > 2 && (raw[0] & 0xFF) == 0x1F && (raw[1] & 0xFF) == 0x8B);
        return new String(gzip ? SecureHttp.gunzip(raw, cap) : raw, StandardCharsets.UTF_8);
    }

    /** The {@code {"error":"..."}} code of a failed response, or {@code null}. */
    private String errorCode(String body) {
        try {
            JsonElement tree = MessageSchemas.parse(MessageTypes.ERROR, body);
            return tree.getAsJsonObject().get("error").getAsString();
        } catch (ValidationException invalid) {
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
