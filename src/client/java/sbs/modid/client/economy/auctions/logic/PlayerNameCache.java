/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.auctions.logic;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Resolves seller UUIDs to player names client-side (the appraise API sends only UUIDs). Uses the
 * public Mojang session profile endpoint, remembers every answer for the session and never blocks:
 * {@link #name(String)} returns {@code null} while a lookup is in flight – callers simply render a
 * placeholder and pick the name up on a later frame. Failed lookups fall back to the short UUID so
 * they are not retried forever.
 */
public final class PlayerNameCache {

// ============================================================================
// [MODERATOR AUDIT / NETWORK DISCLOSURE]
// KEYWORDS: NETWORK_OUTBOUND, NETWORK_INBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
// ENDPOINT: https://sessionserver.mojang.com/session/minecraft/profile/<uuid>
// METHOD: GET
// PURPOSE: Turn an auction's seller uuid into a readable name, so an auction can say who
//   listed it.
// DATA SENT: The uuid being looked up, as the last path segment. No headers, no body, no
//   credentials. The uuid comes from the public auction data, NOT from the local player.
// DATA RECEIVED: Read-only public JSON - that account's current name. Read with Gson as a
//   JsonObject and one string is taken from it.
// SAFETY DECLARATION: This is Mojang's own public name-lookup endpoint and needs no
//   authentication. The uuid sent is a third party's, already public in the auction listing.
//   No session token, no credential of the local player, and no OS telemetry is transmitted.
// ============================================================================
    private static final String PROFILE_URL = "https://sessionserver.mojang.com/session/minecraft/profile/";
    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    private static final PlayerNameCache INSTANCE = new PlayerNameCache();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final Gson gson = new Gson();
    private final Map<String, String> names = new ConcurrentHashMap<>();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SBS-PlayerNames");
        thread.setDaemon(true);
        return thread;
    });

    private PlayerNameCache() {
    }

    public static PlayerNameCache getInstance() {
        return INSTANCE;
    }

    /** The player name for the UUID, or {@code null} while it is still resolving. */
    public String name(String uuid) {
        if (uuid == null || uuid.isEmpty()) {
            return null;
        }
        String key = uuid.replace("-", "").toLowerCase(java.util.Locale.ROOT);
        String known = names.get(key);
        if (known != null) {
            return known;
        }
        if (inFlight.add(key)) {
            executor.execute(() -> resolve(key));
        }
        return null;
    }

    private void resolve(String uuid) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(PROFILE_URL + uuid))
                    .timeout(TIMEOUT).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonObject profile = gson.fromJson(response.body(), JsonObject.class);
                if (profile != null && profile.has("name")) {
                    names.put(uuid, profile.get("name").getAsString());
                    return;
                }
            }
            names.put(uuid, shortUuid(uuid)); // unknown profile – remember the fallback
        } catch (Exception e) {
            names.put(uuid, shortUuid(uuid)); // network failure – do not retry every frame
        } finally {
            inFlight.remove(uuid);
        }
    }

    private static String shortUuid(String uuid) {
        return uuid.length() > 8 ? uuid.substring(0, 8) : uuid;
    }
}
