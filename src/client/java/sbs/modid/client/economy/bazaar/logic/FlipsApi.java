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
import sbs.modid.client.core.api.ApiFailure;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.licence.privacy.ConsentScope;
import sbs.modid.client.economy.bazaar.ui.BestFlipsOverlay;
import sbs.modid.client.economy.pricehistory.logic.PriceApi;

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
 * Client for the Best Flips endpoint ({@code GET /api/flips}): the server ranks the top bazaar
 * flip opportunities (order-spread flips and book-combine craft flips) with the tunable weights
 * from its {@code weights.json}; the mod only renders the result in {@link BestFlipsOverlay}.
 *
 * <p>Auth is the licence token ({@code Authorization: Bearer}), read fresh per request, with the
 * same primary/legacy endpoint fallback as the other SBS APIs. One background daemon thread;
 * callbacks run on it.
 */
public final class FlipsApi {

// ============================================================================
// [MODERATOR AUDIT / NETWORK DISCLOSURE]
// KEYWORDS: NETWORK_OUTBOUND, NETWORK_INBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
// ENDPOINT: https://skyblocksimplified.info/api/flips
// METHOD: GET
// PURPOSE: Pre-calculated bazaar flip margins for the Bazaar Flips page, worked out on the
//   server so the client does not have to crawl the whole bazaar.
// DATA SENT: Query parameters only (budget and result limit). No body.
// DATA RECEIVED: Read-only JSON - item ids, prices and calculated margins. Parsed with Gson.
// SAFETY DECLARATION: Sends the licence token (Authorization: Bearer, X-Api-Token),
//   which identifies the PURCHASE and not the player. No Mojang credentials, no
//   session id, no OS or hardware telemetry. Refused until the user grants
//   ConsentScope.LICENCE_VALIDATION in Licence Token > Privacy & data; every switch there starts
//   off. See PRIVACY.md.
// ============================================================================
    private static final String PATH = "/api/flips";
    private static final Duration TIMEOUT = Duration.ofSeconds(12);

    private static final FlipsApi INSTANCE = new FlipsApi();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final Gson gson = new Gson();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SBS-Flips");
        thread.setDaemon(true);
        return thread;
    });

    /** Result callback: exactly one of {@code result} / {@code failure} is non-null. */
    public interface Callback {
        void done(Response result, ApiFailure failure, String detail);
    }

    private FlipsApi() {
    }

    public static FlipsApi getInstance() {
        return INSTANCE;
    }

    /**
     * Fetches the current flip ranking asynchronously (server caches ~60s internally).
     *
     * @param budget max starting capital in coins; flips costing more are filtered
     *               server-side ({@code ?budget=...}). 0 = unlimited.
     */
    public void fetch(long budget, Callback callback) {
        String query = budget > 0 ? "?budget=" + budget : "";
        executor.execute(() -> {
            if (!sbs.modid.client.core.api.SbsApi.hasLicence()) {
                // Answered here rather than let SbsApi.send refuse it: the refusal arrives as an
                // IOException, which is indistinguishable from the network being down by the time it
                // reaches the catch below.
                callback.done(null, ApiFailure.NO_LICENCE, null);
                return;
            }
            String primary = PriceApi.base();
            try {
                callback.done(get(primary, query), null, null);
            } catch (ApiException answered) {
                callback.done(null, answered.failure, answered.getMessage());
            } catch (Exception unreachable) {
                String legacy = PriceApi.legacyBase();
                if (legacy.equals(primary)) {
                    SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Flips] Request failed: {}",
                            unreachable.toString());
                    callback.done(null, ApiFailure.OFFLINE, unreachable.toString());
                    return;
                }
                try {
                    callback.done(get(legacy, query), null, null);
                } catch (ApiException answeredLegacy) {
                    callback.done(null, answeredLegacy.failure, answeredLegacy.getMessage());
                } catch (Exception e) {
                    SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Flips] Request failed: {}", e.toString());
                    callback.done(null, ApiFailure.OFFLINE, e.toString());
                }
            }
        });
    }

    private Response get(String base, String query) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + PATH + query))
                .timeout(TIMEOUT)
                .header("Accept-Encoding", "gzip")
                .GET();
        String token = sbs.modid.client.core.config.LicenceToken.getInstance().get();
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token.trim());
            builder.header("X-Api-Token", token.trim());
        }
        HttpResponse<byte[]> response = sbs.modid.client.core.api.SbsApi.send(
                ConsentScope.LICENCE_VALIDATION, http, builder.build(),
                HttpResponse.BodyHandlers.ofByteArray());
        String text = inflate(response);
        int status = response.statusCode();
        if (status == 401) {
            throw new ApiException(ApiFailure.EXPIRED, "Invalid licence token");
        }
        if (status == 423) {
            throw new ApiException(ApiFailure.LOCKED, "Token locked to another connection");
        }
        if (status != 200) {
            throw new ApiException(ApiFailure.BACKEND, "HTTP " + status);
        }
        Response result = gson.fromJson(text, Response.class);
        if (result == null || result.flips == null) {
            throw new ApiException(ApiFailure.BACKEND, "Empty response");
        }
        return result;
    }

    /** A readable, user-facing API failure (the endpoint answered – no legacy fallback needed). */
    private static final class ApiException extends Exception {
        private final ApiFailure failure;

        ApiException(ApiFailure failure, String message) {
            super(message);
            this.failure = failure;
        }
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

    // ------------------------------------------------------------------
    // Response model (field names mirror the API JSON exactly)
    // ------------------------------------------------------------------

    public static final class Response {
        public long g;
        /** Timestamp of the bazaar snapshot the ranking is based on (unix seconds). */
        public long data_ts;
        public Flip[] flips;
    }

    public static final class Flip {
        /** {@code "spread"} (buy order → sell offer) or {@code "craft"} (combine books). */
        public String type;
        public String item_id;
        /** Total cost per flip (coins spent, buy-order side). */
        public Double buy;
        /** Revenue per flip after bazaar tax. */
        public Double sell;
        public Double profit;
        public Double margin_pct;
        public Integer volume_week;
        /** Estimated seconds until a buy order of this size fills. */
        public Integer fill_s;
        /** Estimated seconds until the sell offer is bought out. */
        public Integer sell_s;
        /** 0–100, relative to the #1 flip of this ranking. */
        public Double score;
        /** Craft flips only: the base book bought and how many are combined. */
        public String craft_from;
        public Integer craft_count;
    }
}
