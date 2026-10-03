/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.forge.logic;

import com.google.gson.Gson;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.api.ApiFailure;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.licence.privacy.ConsentScope;
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
 * Client for the Forge Flips endpoint ({@code GET /api/forge}): the server knows every forgeable
 * item (recipes + forge durations), prices each one's craft cost against its sale value, and ranks
 * them by profit per forge hour – weighted 50 % right now / 25 % day average / 25 % week average.
 * The mod only renders the result.
 *
 * <p>Auth is the licence token ({@code Authorization: Bearer}), read fresh per request, with the
 * same primary/legacy fallback as the other SBS APIs. One background daemon thread; callbacks run
 * on it. Mirrors {@link sbs.modid.client.economy.bazaar.logic.FlipsApi} on purpose – same shape, same
 * {@link ApiFailure} cases, nothing new to learn.
 *
 * <p><b>Every failure here is a fallback trigger, not a dead end.</b> {@link ForgeFlipFeed} turns any
 * of them into a locally computed ranking, so this class's job is to say <i>which</i> case applied
 * accurately enough for the UI to tell the player what to do about it.
 */
public final class ForgeApi {

// ============================================================================
// [MODERATOR AUDIT / NETWORK DISCLOSURE]
// KEYWORDS: NETWORK_OUTBOUND, NETWORK_INBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
// ENDPOINT: https://skyblocksimplified.info/api/forge
// METHOD: GET
// PURPOSE: Ranked forge crafting profits for the Forge Flips page.
// DATA SENT: Query parameters only (budget, quick-forge level). No body.
// DATA RECEIVED: Read-only JSON - recipe ids, costs, revenues, ratings. Parsed with Gson.
// SAFETY DECLARATION: Sends the licence token (Authorization: Bearer, X-Api-Token),
//   which identifies the PURCHASE and not the player. No Mojang credentials, no
//   session id, no OS or hardware telemetry. Refused until the user grants
//   ConsentScope.LICENCE_VALIDATION in Licence Token > Privacy & data; every switch there starts
//   off. See PRIVACY.md.
// ============================================================================
    private static final String PATH = "/api/forge";
    private static final Duration TIMEOUT = Duration.ofSeconds(12);

    private static final ForgeApi INSTANCE = new ForgeApi();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final Gson gson = new Gson();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SBS-Forge");
        thread.setDaemon(true);
        return thread;
    });

    /** Exactly one of {@code result} / {@code failure} is non-null. */
    public interface Callback {
        void done(Response result, ApiFailure failure, String detail);
    }

    private ForgeApi() {
    }

    public static ForgeApi getInstance() {
        return INSTANCE;
    }

    /**
     * Fetches the current forge ranking asynchronously (the server caches ~60 s internally).
     *
     * @param budget max craft cost in coins; pricier entries are filtered server-side. 0 = unlimited.
     */
    public void fetch(long budget, Callback callback) {
        String query = budget > 0 ? "?budget=" + budget : "";
        executor.execute(() -> {
            if (!sbs.modid.client.core.api.SbsApi.hasLicence()) {
                // Answered here rather than let SbsApi.send refuse it: that refusal arrives as an
                // IOException, which is indistinguishable from the network being down by the time it
                // reaches the catch below - and "connection failed" sends an unlicensed player off to
                // debug their router. FlipsApi learned this the same way.
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
                    SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Forge] Request failed: {}",
                            unreachable.toString());
                    callback.done(null, ApiFailure.OFFLINE, unreachable.toString());
                    return;
                }
                try {
                    callback.done(get(legacy, query), null, null);
                } catch (ApiException answeredLegacy) {
                    callback.done(null, answeredLegacy.failure, answeredLegacy.getMessage());
                } catch (Exception e) {
                    SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Forge] Request failed: {}", e.toString());
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
        if (status == 404) {
            throw new ApiException(ApiFailure.BACKEND, "Server outdated - /api/forge missing");
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

    /** A readable, user-facing API failure (the endpoint answered - no legacy fallback needed). */
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
        /** Timestamp of the bazaar snapshot the ranking is based on (unix seconds). */
        public long data_ts;
        public Flip[] flips;
        public Weights weights;
    }

    /** The rating weights the server used, so the overlay can state them rather than assume. */
    public static final class Weights {
        public Double now;
        public Double day;
        public Double week;
    }

    public static final class Flip {
        public String item_id;
        /** Display name, may carry § colour codes. */
        public String name;
        /** Craft cost of one forge run. */
        public Double buy;
        /** Revenue after bazaar tax. */
        public Double sell;
        public Double profit;
        public Double margin_pct;
        /** Forge time of one run, in seconds. */
        public Integer duration_s;
        public Integer count;
        /** Profit per forge hour, right now. */
        public Double per_hour;
        public Double per_hour_day;
        public Double per_hour_week;
        /** The weighted 50/25/25 figure the ranking sorts by. */
        public Double rating;
        /** 0–100, relative to the #1 entry of this ranking. */
        public Double score;
        public Integer volume_week;
        /** HotM / collection requirement text, e.g. "Requires: HotM 7". */
        public String hotm;
        /** Which price states backed the rating ("now+day+week", "now+week", ...). */
        public String basis;
    }
}
