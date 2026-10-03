/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.essenceshop.logic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.economy.essenceshop.model.ShopPerk;
import sbs.modid.client.economy.recipe.logic.SkyBlockRepoRecipeProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The essence shop perk table, in the mod's own shape: essence id → the perks that shop sells, each
 * with the price of every level.
 *
 * <p><b>Why a table is needed at all.</b> The shop menu states what the <i>next</i> level costs and
 * nothing else, so "what does maxing this perk cost" cannot be answered from the screen - which is
 * the entire question this feature exists to answer. The curve comes from the item-data repository
 * the mod already downloads; the menu only has to supply the current level.
 *
 * <p>Parsed once, lazily, and re-parsed only when the provider hands back a different document (it
 * drops its own parse when a fresh copy is downloaded). A shop whose perks are not in the table is
 * not a failure state: the panel says the table is missing and shows nothing it cannot back up.
 */
public final class EssencePerkData {

    private static final EssencePerkData INSTANCE = new EssencePerkData();

    /** The document the current parse came from, compared by identity to notice a refresh. */
    private volatile JsonObject parsedFrom;

    private volatile Map<String, List<ShopPerk>> byType = Map.of();

    /** Per essence, the perks keyed by their {@linkplain #normalise normalised} display name. */
    private volatile Map<String, Map<String, ShopPerk>> byName = Map.of();

    private EssencePerkData() {
    }

    public static EssencePerkData getInstance() {
        return INSTANCE;
    }

    /** Whether the table has been downloaded and could be read. */
    public boolean available() {
        refreshIfNeeded();
        return !byType.isEmpty();
    }

    /** The perks of one shop in table order, or empty when this essence has no shop. */
    public List<ShopPerk> perks(String essenceId) {
        refreshIfNeeded();
        return byType.getOrDefault(essenceId, List.of());
    }

    /** Whether this essence has a perk shop at all - the test that makes an id a shop currency. */
    public boolean isShopCurrency(String essenceId) {
        refreshIfNeeded();
        return byType.containsKey(essenceId);
    }

    /** Every essence id the table has a shop for. */
    public java.util.Set<String> shopIds() {
        refreshIfNeeded();
        return byType.keySet();
    }

    /** The perk a menu item's name refers to, or {@code null} when the table has no such perk. */
    public ShopPerk perkNamed(String essenceId, String displayName) {
        refreshIfNeeded();
        Map<String, ShopPerk> perks = byName.get(essenceId);
        return perks == null ? null : perks.get(normalise(displayName));
    }

    /**
     * A display name reduced to what two spellings of the same perk have in common: no colour codes
     * (already stripped by the caller), no punctuation, one space between words, lower case. Hypixel
     * writes a name with an apostrophe or an extra space often enough that comparing raw strings
     * loses perks for no reason.
     */
    public static String normalise(String displayName) {
        if (displayName == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(displayName.length());
        boolean space = false;
        for (int i = 0; i < displayName.length(); i++) {
            char c = Character.toLowerCase(displayName.charAt(i));
            if (Character.isLetterOrDigit(c)) {
                if (space && out.length() > 0) {
                    out.append(' ');
                }
                space = false;
                out.append(c);
            } else {
                space = true;
            }
        }
        return out.toString();
    }

    /**
     * How long to wait before asking again once the table has been found missing.
     *
     * <p>The provider answers "not downloaded yet" with a filesystem check, and the caller of this
     * runs while any container is open - so without a pause an install whose table has not arrived
     * would stat a file every frame, forever.
     */
    private static final long MISSING_RECHECK_MS = 5_000L;

    private volatile long recheckAt;

    private void refreshIfNeeded() {
        if (parsedFrom == null && System.currentTimeMillis() < recheckAt) {
            return;
        }
        JsonObject document = SkyBlockRepoRecipeProvider.getInstance().essenceShops();
        if (document == null) {
            parsedFrom = null;
            byType = Map.of();
            byName = Map.of();
            recheckAt = System.currentTimeMillis() + MISSING_RECHECK_MS;
            return;
        }
        if (document == parsedFrom) {
            return;
        }
        parse(document);
    }

    private synchronized void parse(JsonObject document) {
        if (document == parsedFrom) {
            return;   // another thread got here first
        }
        Map<String, List<ShopPerk>> types = new HashMap<>();
        Map<String, Map<String, ShopPerk>> names = new HashMap<>();
        int perkCount = 0;
        for (Map.Entry<String, JsonElement> shop : document.entrySet()) {
            if (!shop.getValue().isJsonObject()) {
                continue;
            }
            String essenceId = shop.getKey().trim().toUpperCase(Locale.ROOT);
            List<ShopPerk> perks = new ArrayList<>();
            Map<String, ShopPerk> named = new HashMap<>();
            for (Map.Entry<String, JsonElement> entry : shop.getValue().getAsJsonObject().entrySet()) {
                ShopPerk perk = readPerk(entry.getKey(), entry.getValue());
                if (perk == null) {
                    continue;
                }
                perks.add(perk);
                // First spelling wins: two perks sharing a name is a table problem, and silently
                // letting the later one replace the earlier would hide it.
                named.putIfAbsent(normalise(perk.name()), perk);
            }
            if (!perks.isEmpty()) {
                types.put(essenceId, List.copyOf(perks));
                names.put(essenceId, Map.copyOf(named));
                perkCount += perks.size();
            }
        }
        byType = Map.copyOf(types);
        byName = Map.copyOf(names);
        parsedFrom = document;
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Essence] Perk table: {} shop(s), {} perk(s).",
                types.size(), perkCount);
    }

    /** One table entry, or {@code null} when it is not a costed perk (a malformed entry is skipped). */
    private static ShopPerk readPerk(String key, JsonElement value) {
        if (key == null || key.isBlank() || value == null || !value.isJsonObject()) {
            return null;
        }
        JsonObject perk = value.getAsJsonObject();
        if (!perk.has("costs") || !perk.get("costs").isJsonArray()) {
            return null;
        }
        JsonArray costs = perk.getAsJsonArray("costs");
        List<Long> levels = new ArrayList<>(costs.size());
        for (JsonElement cost : costs) {
            try {
                long amount = cost.getAsLong();
                if (amount < 0) {
                    return null;   // a negative price is not something to guess at
                }
                levels.add(amount);
            } catch (RuntimeException notANumber) {
                return null;
            }
        }
        if (levels.isEmpty()) {
            return null;
        }
        String name = perk.has("name") && perk.get("name").isJsonPrimitive()
                ? perk.get("name").getAsString() : key;
        return new ShopPerk(key, name, levels);
    }
}
