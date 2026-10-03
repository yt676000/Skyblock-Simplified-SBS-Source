/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.prices;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.SBSFiles;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A persistent, chat-fed supplement to the API price caches.
 *
 * <p>Hypixel announces every order the player sets up in chat, e.g.
 * <pre>[Bazaar] Sell Offer Setup! 64x Enchanted Diamond for 1,000,000 coins.</pre>
 * This class parses those lines (via {@code ChatPriceListenerMixin}), records the <b>unit</b> price
 * ({@code coins / qty}) keyed by the item's normalised SkyBlock id, and persists the whole map to disk
 * ({@code config/skyblock-simplified-sbs-prices.json}). So the data survives restarts – the player
 * never has to re-index by re-opening the Bazaar menu, and it <i>supplements</i> (never replaces) the
 * live API caches: tooltips fall back to it when the API has no entry.
 *
 * <p>Thread-safe: chat is parsed on the client thread, tooltips read on the render thread, so the map
 * is a {@link ConcurrentHashMap}.
 */
public final class ChatPriceCache {

    private static final ChatPriceCache INSTANCE = new ChatPriceCache();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Long>>() {
    }.getType();

    /** Strips every Minecraft formatting code (§ + colour/format char), case-insensitively. */
    private static final Pattern FORMATTING = Pattern.compile("(?i)§[0-9a-fk-or]");

    /**
     * Relaxed matcher for both order-setup lines, tolerant of Hypixel's spacing / localisation:
     * "[Bazaar] … Sell Offer Setup! 64x Enchanted Diamond for 1,000,000 coins." (and the Buy variant).
     * Groups: 1 = quantity, 2 = item name, 3 = coins (with separators).
     */
    private static final Pattern SETUP = Pattern.compile(
            "(?i).*\\[Bazaar].*(?:Sell Offer Setup!|Buy Order Setup!).*?(\\d+)x?\\s+(.*?)\\s+for\\s+([\\d,.]+)\\s+coins.*");

    private final Map<String, Long> unitPrices = new ConcurrentHashMap<>();
    private volatile boolean loaded;

    private ChatPriceCache() {
    }

    public static ChatPriceCache getInstance() {
        return INSTANCE;
    }

    /** Loads the persisted cache once (safe to call repeatedly). */
    public synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path path = filePath();
        try {
            if (Files.exists(path)) {
                try (Reader reader = Files.newBufferedReader(path)) {
                    Map<String, Long> stored = GSON.fromJson(reader, MAP_TYPE);
                    if (stored != null) {
                        unitPrices.putAll(stored);
                    }
                }
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][ChatPrice] Loaded {} cached chat price(s).", unitPrices.size());
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][ChatPrice] Failed to load price cache", e);
        }
    }

    /** Unit price for a SkyBlock id captured from chat / the menu scraper, or {@code null} if none. */
    public Long getUnitPrice(String skyblockId) {
        return skyblockId == null ? null : unitPrices.get(skyblockId);
    }

    /**
     * Stores a unit price for an already-normalised SkyBlock id and persists it. This is the single
     * shared entry point used by <b>both</b> the chat listener and the manual Bazaar menu scraper, so
     * every source writes to the exact same on-disk cache.
     */
    public void putUnitPrice(String skyblockId, long unitPrice) {
        if (skyblockId == null || skyblockId.isEmpty() || unitPrice <= 0) {
            return;
        }
        Long previous = unitPrices.put(skyblockId, unitPrice);
        if (previous == null || previous != unitPrice) {
            save();
        }
    }

    /** Parses a chat line for a Bazaar order-setup message and stores the unit price if it matches. */
    public void parseChat(String rawMessage) {
        if (rawMessage == null) {
            return;
        }
        // Strip all colour / formatting codes first – Hypixel bolds and colours these lines, and the
        // codes would otherwise break the matchers.
        String text = FORMATTING.matcher(rawMessage).replaceAll("");
        Matcher matcher = SETUP.matcher(text);
        if (!matcher.matches()) {
            return;
        }
        try {
            long qty = Long.parseLong(matcher.group(1));
            String name = matcher.group(2).trim();
            // Coins may carry thousands separators and, rarely, a decimal – normalise to a whole number.
            double coins = Double.parseDouble(matcher.group(3).replace(",", ""));
            if (qty <= 0 || coins <= 0) {
                return;
            }
            long unit = Math.round(coins / qty);
            String id = normalizeId(name);
            putUnitPrice(id, unit);
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][ChatPrice] Stored {} = {} coins/unit (from chat).", id, unit);
        } catch (NumberFormatException ignored) {
            // malformed number – skip this line
        }
    }

    /** Normalises a display name to the SkyBlock id convention (e.g. "Enchanted Diamond" -> "ENCHANTED_DIAMOND"). */
    private static String normalizeId(String name) {
        return name.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
    }

    private synchronized void save() {
        Path path = filePath();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(unitPrices, writer);
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][ChatPrice] Failed to save price cache", e);
        }
    }

    private static Path filePath() {
        return SBSFiles.bazaarPricesFile();
    }
}
