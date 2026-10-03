/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.collection;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import sbs.modid.SkyblockSimplifiedSBS;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The SkyBlock collection catalogue: display name → tier requirements, fed by the official
 * {@code /resources/skyblock/collections} endpoint (needs no API key). Fetched async, cached to
 * {@code config/sbs/repo/Collections.json} and refreshed weekly, so the tracker works offline after
 * the first launch.
 */
public final class CollectionCatalog {

// ============================================================================
// [MODERATOR AUDIT / NETWORK DISCLOSURE]
// KEYWORDS: NETWORK_OUTBOUND, NETWORK_INBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
// ENDPOINT: https://api.hypixel.net/v2/resources/skyblock/collections
// METHOD: GET
// PURPOSE: The catalogue of every SkyBlock collection and its tier thresholds, so the
//   collection tracker knows what the next tier needs without hardcoding a table.
// DATA SENT: Nothing in the URL and no body.
// DATA RECEIVED: Read-only public JSON - collection names, tiers and amounts. Parsed field by
//   field with Gson's JsonParser; unknown keys are ignored rather than mapped onto types.
// SAFETY DECLARATION: No user credentials, session tokens, Mojang uuids or OS telemetry
//   are collected or transmitted. No licence token is sent - this endpoint is public.
// ============================================================================
    private static final String URL = "https://api.hypixel.net/v2/resources/skyblock/collections";
    private static final long REFRESH_MS = 7L * 24 * 60 * 60 * 1000;

    /** One collection: item id, pretty name, and the cumulative amount required per tier. */
    public record Info(String id, String name, long[] tierAmounts) {

        /** The requirement of the next tier above {@code current}, or the max tier when capped. */
        public long nextTierAmount(long current) {
            for (long amount : tierAmounts) {
                if (current < amount) {
                    return amount;
                }
            }
            return tierAmounts.length == 0 ? 0 : tierAmounts[tierAmounts.length - 1];
        }

        /** The tier index (1-based) reached with {@code current}; 0 = none yet. */
        public int tierOf(long current) {
            int tier = 0;
            for (long amount : tierAmounts) {
                if (current >= amount) {
                    tier++;
                }
            }
            return tier;
        }

        public int maxTier() {
            return tierAmounts.length;
        }
    }

    /**
     * Lookup index keyed by a NORMALIZED string – upper-cased with every non-alphanumeric char
     * removed – so a collection is found whether you have the collection name ("Hard Stone"), the
     * dropped item's name ("Hardstone") or its id ("HARD_STONE"): all normalize to "HARDSTONE".
     * This is what fixes the count-along missing hardstone/redstone/… whose item name differs from
     * the collection name.
     */
    private static volatile Map<String, Info> byKey = Map.of();

    private CollectionCatalog() {
    }

    /** Kicks off the load (disk cache first, then a weekly online refresh); call once at init. */
    public static void start() {
        Thread thread = new Thread(CollectionCatalog::load, "SBS-CollectionCatalog");
        thread.setDaemon(true);
        thread.start();
    }

    /** Upper-cased, alphanumeric-only key ("Hard Stone"/"Hardstone"/"HARD_STONE" -> "HARDSTONE"). */
    public static String normalizeKey(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    /** The collection matching a name OR an id (normalized), or {@code null}. */
    public static Info match(String nameOrId) {
        String key = normalizeKey(nameOrId);
        return key.isEmpty() ? null : byKey.get(key);
    }

    public static boolean isLoaded() {
        return !byKey.isEmpty();
    }

    private static Path cacheFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("sbs").resolve("repo")
                .resolve("Collections.json");
    }

    private static void load() {
        try {
            Path cache = cacheFile();
            boolean fresh = Files.isRegularFile(cache)
                    && System.currentTimeMillis() - Files.getLastModifiedTime(cache).toMillis() < REFRESH_MS;
            if (Files.isRegularFile(cache)) {
                parse(Files.readString(cache, StandardCharsets.UTF_8));
            }
            if (!fresh) {
                fetch();
            }
        } catch (Throwable t) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Collections] catalogue load failed", t);
        }
    }

    private static void fetch() {
        try (HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15)).build()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(URL))
                    .timeout(Duration.ofSeconds(30)).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Collections] resource HTTP {}",
                        response.statusCode());
                return;
            }
            parse(response.body());
            Files.createDirectories(cacheFile().getParent());
            Files.writeString(cacheFile(), response.body(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Collections] resource fetch failed", t);
        }
    }

    /** {@code collections.<CATEGORY>.items.<ID> = {name, tiers:[{tier, amountRequired}]}}. */
    private static void parse(String json) {
        Map<String, Info> parsed = new HashMap<>();
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject categories = root.getAsJsonObject("collections");
        if (categories == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> category : categories.entrySet()) {
            if (!category.getValue().isJsonObject()) {
                continue;
            }
            JsonObject items = category.getValue().getAsJsonObject().getAsJsonObject("items");
            if (items == null) {
                continue;
            }
            for (Map.Entry<String, JsonElement> item : items.entrySet()) {
                if (!item.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject meta = item.getValue().getAsJsonObject();
                String name = meta.has("name") ? meta.get("name").getAsString() : item.getKey();
                var tiers = meta.getAsJsonArray("tiers");
                if (tiers == null || tiers.isEmpty()) {
                    continue;
                }
                long[] amounts = new long[tiers.size()];
                for (int i = 0; i < tiers.size(); i++) {
                    JsonObject tier = tiers.get(i).getAsJsonObject();
                    amounts[i] = tier.has("amountRequired") ? tier.get("amountRequired").getAsLong() : 0;
                }
                java.util.Arrays.sort(amounts);
                Info info = new Info(item.getKey(), name, amounts);
                // Index under the normalized NAME and the normalized ID, so a lookup by any of the
                // collection name / dropped-item name / item id resolves the same collection.
                parsed.put(normalizeKey(name), info);
                parsed.put(normalizeKey(item.getKey()), info);
            }
        }
        if (!parsed.isEmpty()) {
            byKey = Map.copyOf(parsed);
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Collections] {} collections loaded ({} keys)",
                    new java.util.HashSet<>(parsed.values()).size(), parsed.size());
        }
    }
}
