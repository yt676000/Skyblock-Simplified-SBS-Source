/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.enchants;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.licence.privacy.ConsentScope;
import sbs.modid.client.economy.pricehistory.logic.PriceApi;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Cache for the per-item enchant pool ({@code GET /api/enchants/<item_id>}): every enchant (with
 * the highest level) the SBS database has ever seen on that item id - i.e. the data-driven answer
 * to "which enchants are applicable here". Backs the "Hold Shift: Missing Enchantments" tooltip.
 *
 * <p>Non-blocking: {@link #get} returns immediately (null while loading) and triggers one async
 * fetch per item; results cache for 10 minutes, failures for 30 seconds (no hammering).
 */
public final class EnchantPoolCache {

    // Constants MUST be initialised before INSTANCE: the constructor reads TIMEOUT,
    // and static initialisers run strictly in declaration order (a null TIMEOUT
    // here crashed the whole render thread via ExceptionInInitializerError).
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final long OK_TTL_MS = 10 * 60_000L;
    private static final long FAIL_TTL_MS = 30_000L;

    private static final EnchantPoolCache INSTANCE = new EnchantPoolCache();

    private record Entry(long fetchedAt, Map<String, Integer> pool) {
    }

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final Map<String, Boolean> inFlight = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final Gson gson = new Gson();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SBS-EnchantPool");
        thread.setDaemon(true);
        return thread;
    });

    private EnchantPoolCache() {
    }

    public static EnchantPoolCache getInstance() {
        return INSTANCE;
    }

    /**
     * The enchant pool for an item id, or {@code null} while loading (a fetch is kicked off).
     * Returned map: enchant NBT name (lower-case) -> highest seen level; sorted by name.
     */
    public Map<String, Integer> get(String itemId) {
        String key = itemId.toUpperCase(java.util.Locale.ROOT);
        Entry entry = cache.get(key);
        long now = System.currentTimeMillis();
        if (entry != null && now - entry.fetchedAt() < (entry.pool() != null ? OK_TTL_MS : FAIL_TTL_MS)) {
            return entry.pool();
        }
        if (inFlight.putIfAbsent(key, Boolean.TRUE) == null) {
            executor.execute(() -> fetch(key));
        }
        return entry != null ? entry.pool() : null;
    }

    private void fetch(String itemId) {
        try {
            Map<String, Integer> pool = request(PriceApi.base(), itemId);
            if (pool == null) {
                pool = request(PriceApi.legacyBase(), itemId);
            }
            cache.put(itemId, new Entry(System.currentTimeMillis(), pool));
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.debug("[SBS][Enchants] pool fetch failed: {}", e.toString());
            cache.put(itemId, new Entry(System.currentTimeMillis(), null));
        } finally {
            inFlight.remove(itemId);
        }
    }

    private Map<String, Integer> request(String base, String itemId) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(
// ============================================================================
// [MODERATOR AUDIT / NETWORK DISCLOSURE]
// KEYWORDS: NETWORK_OUTBOUND, NETWORK_INBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
// ENDPOINT: https://skyblocksimplified.info/api/enchants/<itemId>
// METHOD: GET
// PURPOSE: Which enchantments can appear on a given item, so the enchant table helper can
//   group them instead of listing every enchant in the game.
// DATA SENT: The SkyBlock item id as the last path segment. No body.
// DATA RECEIVED: Read-only JSON - enchant ids and levels. Parsed with Gson.
// SAFETY DECLARATION: Sends the licence token (Authorization: Bearer, X-Api-Token),
//   which identifies the PURCHASE and not the player. No Mojang credentials, no
//   session id, no OS or hardware telemetry. Refused until the user grants
//   ConsentScope.LICENCE_VALIDATION in Licence Token > Privacy & data; every switch there starts
//   off. See PRIVACY.md.
// ============================================================================
                            URI.create(base + "/api/enchants/" + itemId))
                    .timeout(TIMEOUT).GET();
            String token = sbs.modid.client.core.config.LicenceToken.getInstance().get();
            if (token != null && !token.isBlank()) {
                builder.header("Authorization", "Bearer " + token.trim());
            }
            HttpResponse<String> response = sbs.modid.client.core.api.SbsApi.send(
                    ConsentScope.LICENCE_VALIDATION, http, builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                return null;
            }
            JsonObject body = gson.fromJson(response.body(), JsonObject.class);
            if (body == null || !body.has("enchants")) {
                return null;
            }
            Map<String, Integer> pool = new TreeMap<>();
            for (var e : body.getAsJsonObject("enchants").entrySet()) {
                try {
                    pool.put(e.getKey().toLowerCase(java.util.Locale.ROOT),
                            e.getValue().getAsInt());
                } catch (Exception ignored) {
                }
            }
            return pool;
        } catch (Exception e) {
            return null;
        }
    }
}
