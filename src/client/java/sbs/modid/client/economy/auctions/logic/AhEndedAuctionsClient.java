/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.auctions.logic;

import com.google.gson.Gson;
import sbs.modid.SkyblockSimplifiedSBS;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Thin client for Hypixel's public "recently ended auctions" endpoint
 * ({@code https://api.hypixel.net/v2/skyblock/auctions_ended}) - the keyless feed of every auction
 * that <b>sold or ended</b> in roughly the last minute, refreshed server-side about once a minute.
 *
 * <p>This is how the flip feed detects that a listed flip was bought without the SBS cloud server
 * doing anything: whenever a tracked flip's auction id shows up here, it is gone from the market and
 * is removed from every flip output (see {@code AhFlipClient.soldLoop}). Uses the JDK's built-in
 * {@link HttpClient} and bundled Gson, exactly like {@link sbs.modid.client.economy.prices.LbinApiClient};
 * requests are blocking and run on the flip-alert background thread, never the client thread.
 */
public final class AhEndedAuctionsClient {

// ============================================================================
// [MODERATOR AUDIT / NETWORK DISCLOSURE]
// KEYWORDS: NETWORK_OUTBOUND, NETWORK_INBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
// ENDPOINT: https://api.hypixel.net/v2/skyblock/auctions_ended
// METHOD: GET
// PURPOSE: The last hour of completed auctions, used to price items from what they actually
//   sold for rather than from what sellers are asking.
// DATA SENT: Nothing in the URL and no body. Headers only: User-Agent, plus the user's own
//   Hypixel developer key if they configured one (see HypixelApi.withHeaders).
// DATA RECEIVED: Read-only public JSON - item ids, sale prices, timestamps. Parsed with Gson
//   into a fixed Response record.
// SAFETY DECLARATION: No user credentials, session tokens, Mojang uuids or OS telemetry
//   are collected or transmitted. No licence token is sent - this endpoint is public.
// ============================================================================
    private static final String URL = "https://api.hypixel.net/v2/skyblock/auctions_ended";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final Gson gson = new Gson();

    /** Fetches the recently-ended auctions, or {@code null} on any network / parse failure. */
    public Response fetch() {
        try {
            HttpRequest request = sbs.modid.client.core.api.HypixelApi.withHeaders(
                            HttpRequest.newBuilder(URI.create(URL)).timeout(TIMEOUT))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }
            Response parsed = gson.fromJson(response.body(), Response.class);
            return (parsed != null && parsed.auctions != null) ? parsed : null;
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.debug("[SBS][Flips] ended-auctions request failed: {}", e.toString());
            return null;
        }
    }

    /** Top-level response. Field names mirror the API JSON exactly; only what the watcher needs. */
    public static final class Response {
        public boolean success;
        public List<Ended> auctions;
    }

    /** One ended (sold or expired) auction. Only {@code auction_id} is used to match tracked flips. */
    public static final class Ended {
        public String auction_id;
        public String seller;
        public String buyer;
        public long price;
        public boolean bin;
    }
}
