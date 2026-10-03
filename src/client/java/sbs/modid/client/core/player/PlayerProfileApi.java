/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.player;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.api.SbsApi;
import sbs.modid.client.core.licence.privacy.ConsentScope;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Client for the SBS profile-viewer endpoint ({@code GET /api/pv?name=<ign>}): the server resolves
 * the name via Mojang, pulls the Hypixel profiles with ITS key (15-min cache) and returns the
 * fully derived display payload – skills, slayers, catacombs + classes, misc stats, pets with
 * levels, worn armor. The client only renders. Licence-token auth, primary/legacy base fallback
 * exactly like {@link sbs.modid.client.economy.auctions.logic.AppraiseApi}.
 */
public final class PlayerProfileApi {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final PlayerProfileApi INSTANCE = new PlayerProfileApi();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SBS-PlayerViewer");
        thread.setDaemon(true);
        return thread;
    });

    /** Exactly one of {@code result} / {@code error} is non-null. */
    public interface Callback {
        void done(JsonObject result, String error);
    }

    private PlayerProfileApi() {
    }

    public static PlayerProfileApi getInstance() {
        return INSTANCE;
    }

    public void fetch(String playerName, Callback callback) {
        executor.execute(() -> {
// ============================================================================
// [MODERATOR AUDIT / NETWORK DISCLOSURE]
// KEYWORDS: NETWORK_OUTBOUND, NETWORK_INBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
// ENDPOINT: https://skyblocksimplified.info/api/pv?name=<name>
// METHOD: GET
// PURPOSE: The Player Viewer - looks up a public SkyBlock profile by name, the same data any
//   stats site shows.
// DATA SENT: The name being looked up, URL-encoded as a query parameter, plus the licence-token
//   headers. That name is the one the user typed or clicked, never harvested.
// DATA RECEIVED: Read-only JSON - that account public profile data. Parsed with Gson.
// SAFETY DECLARATION: Sends the licence token (Authorization: Bearer, X-Api-Token),
//   which identifies the PURCHASE and not the player. No Mojang credentials, no
//   session id, no OS or hardware telemetry. Refused until the user grants
//   ConsentScope.PROFILE_DATA in Licence Token > Privacy and data; every switch there
//   starts off. See PRIVACY.md.
// ============================================================================
            String path = "/api/pv?name=" + URLEncoder.encode(playerName, StandardCharsets.UTF_8);
            try {
                callback.done(get(SbsApi.base() + path), null);
            } catch (ApiException answered) {
                callback.done(null, answered.getMessage());
            } catch (Exception unreachable) {
                String legacy = SbsApi.legacyBase();
                if (legacy.equals(SbsApi.base())) {
                    callback.done(null, "Connection failed");
                    return;
                }
                try {
                    callback.done(get(legacy + path), null);
                } catch (ApiException answeredLegacy) {
                    callback.done(null, answeredLegacy.getMessage());
                } catch (Exception e) {
                    SkyblockSimplifiedSBS.LOGGER.warn("[SBS][PV] Request failed: {}", e.toString());
                    callback.done(null, "Connection failed");
                }
            }
        });
    }

    private JsonObject get(String url) throws Exception {
        HttpRequest request = SbsApi.withLicence(HttpRequest.newBuilder(URI.create(url))
                        .timeout(TIMEOUT)
                        .header("Accept-Encoding", "gzip"))
                .GET()
                .build();
        HttpResponse<byte[]> response =
                SbsApi.send(ConsentScope.PROFILE_DATA, http, request,
                        HttpResponse.BodyHandlers.ofByteArray());
        String body = inflate(response);
        JsonObject json;
        try {
            json = JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            throw new ApiException("Bad response (HTTP " + response.statusCode() + ")");
        }
        if (response.statusCode() == 200 && !json.has("error")) {
            return json;
        }
        String code = json.has("error") ? json.get("error").getAsString() : "HTTP " + response.statusCode();
        throw new ApiException(switch (code) {
            case "unknown_player" -> "Unknown player";
            case "invalid_token" -> "Invalid licence token";
            case "server_no_hypixel_key" -> "Server has no Hypixel key";
            // The backend's 404 handler: the route itself is missing -> old server build.
            case "not_found" -> "Server outdated - /api/pv missing (patch api_server.py)";
            default -> code;
        });
    }

    /** Inflates a gzip body (the server compresses larger payloads). */
    private static String inflate(HttpResponse<byte[]> response) throws Exception {
        byte[] raw = response.body();
        boolean gzip = response.headers().firstValue("Content-Encoding")
                .map(v -> v.toLowerCase(java.util.Locale.ROOT).contains("gzip")).orElse(false)
                || (raw.length > 2 && (raw[0] & 0xFF) == 0x1F && (raw[1] & 0xFF) == 0x8B);
        if (!gzip) {
            return new String(raw, StandardCharsets.UTF_8);
        }
        try (var in = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(raw))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static final class ApiException extends Exception {
        ApiException(String message) {
            super(message);
        }
    }
}
