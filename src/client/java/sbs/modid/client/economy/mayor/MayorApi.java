/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.mayor;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.licence.privacy.ConsentScope;
import sbs.modid.client.economy.pricehistory.logic.PriceApi;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;

/**
 * Client for the read-only mayor endpoint ({@code GET /api/mayor}): the active SkyBlock mayor with
 * their perks + minister, and the running election's candidates with their live vote counts. The
 * server does all the work; the mod only renders the {@link MayorScreen}.
 *
 * <p>Auth is the licence token ({@code Authorization: Bearer}), read fresh per request, with the
 * same primary/legacy fallback as the other SBS APIs. One background daemon thread; callbacks run on
 * it. Deliberately mirrors {@link sbs.modid.client.economy.forge.logic.ForgeApi} - same shape, same failure
 * messages, nothing new to learn.
 *
 * <p><b>Robustness:</b> every response field is boxed / kept as raw {@link JsonArray}, so a missing
 * {@code active}, {@code election}, perk list or candidate never crashes the screen - it simply
 * renders nothing for that part. Most data is thin at first (the server is still gathering history),
 * so "show nothing" is the normal case.
 */
public final class MayorApi {

// ============================================================================
// [MODERATOR AUDIT / NETWORK DISCLOSURE]
// KEYWORDS: NETWORK_OUTBOUND, NETWORK_INBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
// ENDPOINT: https://skyblocksimplified.info/api/mayor
// METHOD: GET
// PURPOSE: The current SkyBlock mayor, their perks and the election calendar, for /sbs mayor
//   and the inflation warning on item appraisals.
// DATA SENT: Nothing in the query string and no body.
// DATA RECEIVED: Read-only JSON - mayor name, perks, dates. Parsed with Gson.
// SAFETY DECLARATION: Sends the licence token (Authorization: Bearer, X-Api-Token),
//   which identifies the PURCHASE and not the player. No Mojang credentials, no
//   session id, no OS or hardware telemetry. Refused until the user grants
//   ConsentScope.LICENCE_VALIDATION in Licence Token > Privacy & data; every switch there starts
//   off. See PRIVACY.md.
// ============================================================================
    private static final String PATH = "/api/mayor";
    private static final Duration TIMEOUT = Duration.ofSeconds(12);

    private static final MayorApi INSTANCE = new MayorApi();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final Gson gson = new Gson();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SBS-Mayor");
        thread.setDaemon(true);
        return thread;
    });

    /** Exactly one of {@code result} / {@code error} is non-null. */
    public interface Callback {
        void done(Response result, String error);
    }

    private MayorApi() {
    }

    public static MayorApi getInstance() {
        return INSTANCE;
    }

    /** Fetches the active mayor + running election asynchronously (the server caches internally). */
    public void fetch(Callback callback) {
        fetch(null, callback);
    }

    /**
     * Fetches the mayor data, optionally for a specific item ({@code itemId} non-blank adds
     * {@code ?item=<ID>}, which makes the server include the item's {@code current_effect} and the
     * per-mayor {@code history}). A blank/null item fetches the base active + election data only.
     */
    public void fetch(String itemId, Callback callback) {
        String query = itemId != null && !itemId.isBlank()
                ? "?item=" + URLEncoder.encode(itemId.trim(), StandardCharsets.UTF_8) : "";
        executor.execute(() -> {
            String primary = PriceApi.base();
            try {
                callback.done(get(primary, query), null);
            } catch (ApiException answered) {
                callback.done(null, answered.getMessage());
            } catch (Exception unreachable) {
                String legacy = PriceApi.legacyBase();
                if (legacy.equals(primary)) {
                    SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Mayor] Request failed: {}",
                            unreachable.toString());
                    callback.done(null, "Connection failed");
                    return;
                }
                try {
                    callback.done(get(legacy, query), null);
                } catch (ApiException answeredLegacy) {
                    callback.done(null, answeredLegacy.getMessage());
                } catch (Exception e) {
                    SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Mayor] Request failed: {}", e.toString());
                    callback.done(null, "Connection failed");
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
            throw new ApiException("Invalid licence token");
        }
        if (status == 423) {
            throw new ApiException("Token locked to another connection");
        }
        if (status == 404) {
            throw new ApiException("Server outdated - /api/mayor missing");
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

    private static final class ApiException extends Exception {
        ApiException(String message) {
            super(message);
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
    // Response model (field names mirror the API JSON exactly; all optional)
    // ------------------------------------------------------------------

    public static final class Response {
        /** The active mayor block, or {@code null} when the server sent none. */
        public Active active;
        /** The running election block, or {@code null} when the server sent none. */
        public Election election;
        /**
         * The queried item's current price effect ({@code ?item=} requests only) - same shape as the
         * appraise mayor block. {@code null} when no item was requested or the server sent none.
         */
        public CurrentEffect current_effect;
        /**
         * Per-mayor price history ({@code ?item=} requests only), keyed by mayor name, each
         * {@code {available, reason, n_terms_recorded}}. Kept as a raw object and read defensively
         * ({@link #historyEntry}) because it is thin at first - {@code n_terms_recorded == 0} for
         * everyone until a term completes, which is the normal "no history yet" state, not an error.
         */
        public JsonObject history;
    }

    /**
     * The queried item's current mayor price effect, mirroring the appraise mayor block. All boxed:
     * a missing field stays {@code null}. {@code available == false} carries a {@code reason} instead
     * of the numbers (e.g. {@code insufficient_current}).
     */
    public static final class CurrentEffect {
        public Boolean available;
        public String mayor_name;
        public Integer election_year;
        /** Signed percent the current median sits off normal (− below / deflation, + above / inflation). */
        public Double effect_pct;
        public Double current_median;
        public Double baseline_median;
        public Integer n_current;
        public Integer n_baseline;
        public String note;
        public String reason;
    }

    /** One per-mayor history entry read defensively from the {@link Response#history} object. */
    public record HistoryEntry(boolean available, String reason, int termsRecorded) {
    }

    /**
     * Reads a mayor's {@link HistoryEntry} from the raw {@code history} object, or {@code null} when
     * absent / malformed. Never throws - a thin or oddly-shaped history block just yields no entry.
     */
    public static HistoryEntry historyEntry(JsonObject history, String mayorName) {
        if (history == null || mayorName == null) {
            return null;
        }
        try {
            if (!history.has(mayorName) || !history.get(mayorName).isJsonObject()) {
                return null;
            }
            JsonObject entry = history.getAsJsonObject(mayorName);
            boolean available = entry.has("available") && entry.get("available").isJsonPrimitive()
                    && entry.get("available").getAsBoolean();
            String reason = entry.has("reason") && entry.get("reason").isJsonPrimitive()
                    ? entry.get("reason").getAsString() : null;
            int terms = entry.has("n_terms_recorded") && entry.get("n_terms_recorded").isJsonPrimitive()
                    ? entry.get("n_terms_recorded").getAsInt() : 0;
            return new HistoryEntry(available, reason, Math.max(0, terms));
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    public static final class Active {
        public String mayor_name;
        public Integer election_year;
        public String minister_name;
        /** Perk entries - shape is server-defined (strings or {@code {name,description}} objects). */
        public JsonArray perks;
        /** The minister's perk, when the server sends it separately. */
        public JsonArray minister_perks;
    }

    public static final class Election {
        public Integer year;
        public Candidate[] candidates;
    }

    public static final class Candidate {
        public String name;
        /** The candidate's perk group key, e.g. {@code "pets"}. */
        public String key;
        public Long votes;
        public JsonArray perks;
    }

    /** Reads a perk entry's display label from either a bare string or a {@code {name,...}} object. */
    public static String perkLabel(com.google.gson.JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        try {
            if (element.isJsonPrimitive()) {
                return element.getAsString();
            }
            if (element.isJsonObject()) {
                JsonObject object = element.getAsJsonObject();
                for (String key : new String[] {"name", "perk", "title"}) {
                    if (object.has(key) && object.get(key).isJsonPrimitive()) {
                        return object.get(key).getAsString();
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // a malformed perk entry just contributes no label
        }
        return null;
    }

    /** Reads a perk entry's optional description, for a second muted line. */
    public static String perkDescription(com.google.gson.JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        try {
            JsonObject object = element.getAsJsonObject();
            for (String key : new String[] {"description", "desc", "text"}) {
                if (object.has(key) && object.get(key).isJsonPrimitive()) {
                    return object.get(key).getAsString();
                }
            }
        } catch (RuntimeException ignored) {
            // no description available
        }
        return null;
    }
}
