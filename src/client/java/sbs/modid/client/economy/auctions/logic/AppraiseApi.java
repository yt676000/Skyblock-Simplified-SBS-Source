/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.auctions.logic;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.licence.privacy.ConsentScope;
import sbs.modid.client.economy.auctions.ui.SimilarAuctionsOverlay;
import sbs.modid.client.economy.pricehistory.logic.PriceApi;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;

/**
 * Client for the SkyBlock Simplified appraise API ({@code POST /api/appraise}): sends the hovered
 * item's id plus its {@code ExtraAttributes} fields 1:1 as JSON (minus {@code id}; the server does
 * ALL normalising and matching – the mod contains no price / match logic) and receives the
 * appraisal: estimated value, live identical/similar auctions and the sale history with
 * time-to-sell, consumed by the {@link SimilarAuctionsOverlay}.
 *
 * <p>Auth is the licence token ({@code Authorization: Bearer}), read fresh per request. Responses
 * are requested gzipped and inflated manually. One background daemon thread; callbacks run on it.
 */
public final class AppraiseApi {

// ============================================================================
// [MODERATOR AUDIT / NETWORK DISCLOSURE]
// KEYWORDS: NETWORK_OUTBOUND, NETWORK_INBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
// ENDPOINT: https://skyblocksimplified.info/api/appraise
// METHOD: POST
// PURPOSE: Value one item from its attributes - the networked tier of item appraisal, used
//   where the offline estimate cannot price an item.
// DATA SENT: A JSON body describing THE ITEM ONLY: its SkyBlock item id plus the attribute
//   names and numbers read off its own tooltip (see attributesJson). Built with Gson, never
//   string-concatenated. No inventory contents, no player name, no uuid, no profile id.
// DATA RECEIVED: Read-only JSON - an estimated value and its components. Parsed with Gson.
// SAFETY DECLARATION: Sends the licence token (Authorization: Bearer, X-Api-Token),
//   which identifies the PURCHASE and not the player. No Mojang credentials, no
//   session id, no OS or hardware telemetry. Refused until the user grants
//   ConsentScope.PROFILE_DATA in Licence Token > Privacy & data; every switch there starts
//   off. See PRIVACY.md.
// ============================================================================
    private static final String PATH = "/api/appraise";
    private static final Duration TIMEOUT = Duration.ofSeconds(12);

    private static final AppraiseApi INSTANCE = new AppraiseApi();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final Gson gson = new Gson();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SBS-Appraise");
        thread.setDaemon(true);
        return thread;
    });

    /** Result callback: exactly one of {@code result} / {@code error} is non-null. */
    public interface Callback {
        void done(Response result, String error);
    }

    private AppraiseApi() {
    }

    public static AppraiseApi getInstance() {
        return INSTANCE;
    }

    /**
     * Appraises one item asynchronously; {@code attributes} may be empty for catalogue items.
     * Endpoint selection mirrors {@code PriceApi}: the shared active/HTTPS base first, then the
     * legacy direct-port endpoint when the connection fails outright (443 not serving yet).
     */
    public void appraise(String itemId, JsonObject attributes, Callback callback) {
        executor.execute(() -> {
            JsonObject body = new JsonObject();
            body.addProperty("item_id", itemId);
            body.add("attributes", attributes);
            String payload = gson.toJson(body);

            String primary = PriceApi.base();
            try {
                callback.done(post(primary, payload), null);
            } catch (ApiException answered) {
                callback.done(null, answered.getMessage());
            } catch (Exception unreachable) {
                String legacy = PriceApi.legacyBase();
                if (legacy.equals(primary)) {
                    SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Appraise] Request failed: {}",
                            unreachable.toString());
                    callback.done(null, "Connection failed");
                    return;
                }
                try {
                    callback.done(post(legacy, payload), null);
                } catch (ApiException answeredLegacy) {
                    callback.done(null, answeredLegacy.getMessage());
                } catch (Exception e) {
                    SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Appraise] Request failed: {}", e.toString());
                    callback.done(null, "Connection failed");
                }
            }
        });
    }

    /** One blocking POST against one base; API errors become readable {@link ApiException}s. */
    private Response post(String base, String payload) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + PATH))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept-Encoding", "gzip")
                .POST(HttpRequest.BodyPublishers.ofString(payload));
        String token = sbs.modid.client.core.config.LicenceToken.getInstance().get();
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token.trim());
            builder.header("X-Api-Token", token.trim());
        }
        HttpResponse<byte[]> response = sbs.modid.client.core.api.SbsApi.send(
                ConsentScope.PROFILE_DATA, http, builder.build(),
                HttpResponse.BodyHandlers.ofByteArray());
        String text = inflate(response);
        int status = response.statusCode();
        if (status == 401) {
            throw new ApiException("Invalid licence token");
        }
        if (status == 423) {
            throw new ApiException("Token locked to another connection");
        }
        if (status != 200) {
            throw new ApiException("HTTP " + status);
        }
        Response result = gson.fromJson(text, Response.class);
        if (result == null) {
            throw new ApiException("Empty response");
        }
        return result;
    }

    /** A readable, user-facing API failure (the endpoint answered – no legacy fallback needed). */
    private static final class ApiException extends Exception {
        ApiException(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------
    // Request building
    // ------------------------------------------------------------------

    /**
     * The appraise {@code attributes} JSON: the {@code ExtraAttributes} compound complete and
     * unchanged – the server now values dyes, skins, runes, scrolls, Kuudra attributes, drill
     * parts, etherwarp, tuners and every other modifier itself, so NOTHING is filtered client-side
     * except {@code uuid} / {@code timestamp} (identity noise, per API spec). Binary array blobs
     * (backpack {@code *_data}) still stay out – pure request weight, never price-relevant.
     */
    public static JsonObject attributesJson(CompoundTag extra) {
        JsonObject json = new JsonObject();
        for (Map.Entry<String, Tag> entry : extra.entrySet()) {
            String key = entry.getKey();
            if ("uuid".equalsIgnoreCase(key) || "timestamp".equalsIgnoreCase(key)) {
                continue;
            }
            JsonElement value = toJson(entry.getValue());
            if (value != null) {
                json.add(key, value);
            }
        }
        return json;
    }

    private static JsonElement toJson(Tag tag) {
        if (tag instanceof CompoundTag compound) {
            JsonObject object = new JsonObject();
            for (Map.Entry<String, Tag> entry : compound.entrySet()) {
                JsonElement value = toJson(entry.getValue());
                if (value != null) {
                    object.add(entry.getKey(), value);
                }
            }
            return object;
        }
        if (tag instanceof ListTag list) {
            JsonArray array = new JsonArray();
            for (Tag element : list) {
                JsonElement value = toJson(element);
                if (value != null) {
                    array.add(value);
                }
            }
            return array;
        }
        if (tag instanceof StringTag string) {
            return new JsonPrimitive(string.value());
        }
        if (tag instanceof NumericTag numeric) {
            return new JsonPrimitive(numeric.box());
        }
        return null; // byte/int/long array blobs
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
        public Double value;
        public String value_basis;
        public Integer value_n;
        public Double value_max_distance;
        /** Market value of every extra on the requested item, in coins ({@code null} when absent). */
        public Long extras_value;
        /**
         * Unusual-price-increase warning, {@code null} when the item's market is calm: median unit
         * price of the last {@code recent_hours} vs the {@code baseline_days} before that (whole
         * item id, all variants). Only sent when recent ≥ baseline × spike factor with enough
         * samples on both sides – rendered as a warning line by the Similar Auctions window.
         */
        public PriceSpike price_spike;
        public double[] lbin;
        public Tiers tiers;
        public double[][] recent;
        /**
         * Live auction rows {@code [auction_id, unitPrice, count, sellerUUID, end_at, distance,
         * match]} (mixed types; the last two are optional – older responses carry only five).
         */
        public JsonArray live_cheapest;
        /**
         * Closest comparison items: objects with {@code source} ("live" = open auction), {@code
         * price}, {@code count}, {@code auction_id}, {@code seller}, {@code end_at}, {@code
         * distance}, {@code ts} (sale time for sales, snapshot time for live entries), {@code adj}
         * (signed coin delta of the requested item vs this listing at real DB prices: + what it has
         * on top, − what it lacks), {@code est} ({@code price + adj} – the requested item's value
         * by this comparison), optional {@code match} (0–100) and {@code diff.they_have / you_have}
         * entries ({@code {t, k, you, they, w}}) listing every value difference with its coin
         * weight; the diff weights sum (you_have +w, they_have −w) to exactly {@code adj}.
         */
        public JsonArray closest;
        /**
         * The active mayor's effect on this item's price, or {@code null} when the server sent no
         * mayor block. Every field is boxed: a missing key stays {@code null} and never crashes the
         * render, and {@code available == false} (a {@code reason} instead of the numbers) means
         * "not enough data yet" - the normal early case, shown as nothing.
         */
        public Mayor mayor;
    }

    /**
     * Mayor price-awareness for the appraised item (see {@link Response#mayor}). When
     * {@code available} is true the numbers are populated and {@code note} carries the server's
     * human summary; when it is false only {@code reason} is set (e.g. {@code insufficient_current}).
     */
    public static final class Mayor {
        public Boolean available;
        public String mayor_name;
        public Integer election_year;
        /** Signed percent the current median sits off the item's normal price (− below, + above). */
        public Double effect_pct;
        public Double current_median;
        public Double baseline_median;
        public Integer n_current;
        public Integer n_baseline;
        /** Server-composed human summary, e.g. "currently ~-12% below normal under Mayor Paul". */
        public String note;
        /** Why the block is unavailable ({@code available == false}), e.g. {@code insufficient_current}. */
        public String reason;
    }

    public static final class Tiers {
        public Tier exact;
        public Tier similar;
        public Tier base;
    }

    public static final class Tier {
        public int n;
        public Double median;
        public Double avg;
        public Double min;
        public Double max;
        public Last last;
        public Long oldest_ts;
        public SoldIn sold_in;
    }

    public static final class Last {
        public long ts;
        public double unit;
    }

    public static final class SoldIn {
        public Double median;
        public Double avg;
        public Integer n;
    }

    /** Price-spike warning payload (see {@link Response#price_spike}). */
    public static final class PriceSpike {
        /** recent ÷ baseline, e.g. {@code 1.97} = nearly doubled. */
        public Double factor;
        /** Median unit price of the recent window. */
        public Double recent;
        /** Median unit price of the baseline window before it. */
        public Double baseline;
        public Integer recent_hours;
        public Integer baseline_days;
    }

}
