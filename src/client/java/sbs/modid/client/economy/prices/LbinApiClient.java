/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.prices;

import com.google.gson.Gson;
import sbs.modid.SkyblockSimplifiedSBS;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Thin client for the public, paginated Hypixel auctions endpoint
 * ({@code https://api.hypixel.net/v2/skyblock/auctions}).
 *
 * <p>Uses the JDK's built-in {@link HttpClient} (no Fabric / third-party networking) and the bundled
 * Gson, mirroring {@code BazaarApiClient}. Only the fields needed for Lowest-BIN aggregation are
 * mapped. Requests are blocking and run on the {@link LbinCache} background thread.
 */
public final class LbinApiClient {

// ============================================================================
// [MODERATOR AUDIT / NETWORK DISCLOSURE]
// KEYWORDS: NETWORK_OUTBOUND, NETWORK_INBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
// ENDPOINT: https://api.hypixel.net/v2/skyblock/auctions?page=<n>
// METHOD: GET
// PURPOSE: Crawl the live auction pages to find the lowest bin per item - the licence-free
//   fallback for auction prices when our own backend is not available.
// DATA SENT: Only the page number in the query string. No body.
// DATA RECEIVED: Read-only public JSON - one page of live auctions. Parsed with Gson into a
//   fixed Response record.
// SAFETY DECLARATION: No user credentials, session tokens, Mojang uuids or OS telemetry
//   are collected or transmitted. No licence token is sent - this endpoint is public.
// ============================================================================
    private static final String URL = "https://api.hypixel.net/v2/skyblock/auctions?page=";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final Gson gson = new Gson();

    /** Fetches one auctions page, or {@code null} on any network / parse failure. */
    public Response fetch(int page) {
        try {
            HttpRequest request = sbs.modid.client.core.api.HypixelApi.withHeaders(
                            HttpRequest.newBuilder(URI.create(URL + page)).timeout(TIMEOUT))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }
            Response parsed = gson.fromJson(response.body(), Response.class);
            return (parsed != null && parsed.success && parsed.auctions != null) ? parsed : null;
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][LBIN] Auctions API request (page {}) failed: {}", page, e.toString());
            return null;
        }
    }

    /** Top-level response for one page. Field names mirror the API JSON exactly. */
    public static final class Response {
        public boolean success;
        public int page;
        public int totalPages;
        public List<Auction> auctions;
    }

    /**
     * One auction. Only BIN auctions with a starting bid are used; matching is by item name.
     *
     * <p>{@code uuid} and {@code end} were not read until the live-auction index needed them. They
     * were always in the payload: this is a parse change, not a new request. {@code uuid} is the id
     * {@code /viewauction} takes, and it is validated before any command is built from it.
     */
    public static final class Auction {
        public boolean bin;
        public long starting_bid;
        public String item_name;
        /** The auction id, 32 hex chars. */
        public String uuid;
        /** When the auction ends, epoch millis. */
        public long end;
    }
}
