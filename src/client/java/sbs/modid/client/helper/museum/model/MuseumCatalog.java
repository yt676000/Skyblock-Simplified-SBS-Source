/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.museum.model;

import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the Museum takes and what each donation pays, built from Hypixel's keyless items resource -
 * the only source this feature uses for it. No Minecraft types, so it is unit-tested against a
 * trimmed copy of the real resource.
 *
 * <p><b>Donations, not items.</b> A donation is keyed either by an item id or, for an armor set, by
 * {@code SET:<set id>}. The resource says so itself: armor pieces never carry {@code donation_xp},
 * only {@code armor_set_donation_xp: {SET: xp}}, and a piece can belong to two sets at once
 * ({@code BLAZE_HELMET} is in {@code BLAZE} and {@code CRIMSON_HUNTER}). Counting pieces instead of
 * sets gets both the missing count and the XP wrong.
 *
 * <p>The facts this relies on, and how they were checked, are in {@code docs/features/museum-helper.md}:
 * seven categories, Special as a separate bucket ({@code museum: true} without {@code museum_data}),
 * {@code parent} pointing forward along a tier chain (tier N to N+1, sets to sets), and
 * {@code mapped_item_ids} naming the variants a base accepts.
 */
public final class MuseumCatalog {

    public enum Category {
        COMBAT("Combat"),
        DUNGEONEERING("Dungeoneering"),
        FARMING("Farming"),
        FISHING("Fishing"),
        MINING("Mining"),
        FORAGING("Foraging"),
        HUNTING("Hunting"),
        /** Not a {@code museum_data} category: items flagged {@code museum: true} without it. */
        SPECIAL("Special");

        private final String label;

        Category(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** The category a menu title word names ("Combat"), or {@code null}. */
        public static Category byLabel(String word) {
            if (word == null) {
                return null;
            }
            String clean = word.trim();
            for (Category category : values()) {
                if (category.label.equalsIgnoreCase(clean) || category.name().equalsIgnoreCase(clean)) {
                    return category;
                }
            }
            return null;
        }
    }

    /** One thing the Museum accepts. */
    public record Donation(String key, String displayName, Category category, int xp, boolean set,
                           List<String> pieces, String lowerTier, String higherTier) {

        /** The set id for a set donation ({@code SET:FAIRY} gives {@code FAIRY}), else {@code null}. */
        public String setId() {
            return set ? key.substring(SET_PREFIX.length()) : null;
        }
    }

    public static final String SET_PREFIX = "SET:";

    // ------------------------------------------------------------------ resource mirror (Gson)

    /** The part of the items resource this feature reads. Field names are the resource's. */
    public static final class ApiResponse {
        public boolean success;
        public long lastUpdated;
        public List<ApiItem> items;
    }

    public static final class ApiItem {
        public String id;
        public String name;
        public Boolean museum;
        public ApiMuseumData museum_data;
    }

    public static final class ApiMuseumData {
        public Integer donation_xp;
        public String category;
        public Map<String, String> parent;
        public List<String> mapped_item_ids;
        public Map<String, Integer> armor_set_donation_xp;
        public String game_stage;
    }

    /** What the builder takes per item: the id, its display name and the two museum fields. */
    public record Input(String id, String name, boolean museumFlag, ApiMuseumData data) {
    }

    // ------------------------------------------------------------------ the catalogue

    private static volatile MuseumCatalog current = new MuseumCatalog(Map.of(), Map.of(), Map.of(), 0L);

    /** Donation key to donation. */
    private final Map<String, Donation> byKey;
    /** Upper-case item id to every donation key it counts toward (usually one; two for shared pieces). */
    private final Map<String, List<String>> keysForItem;
    /** Variant item id to the base item id it satisfies ({@code STARRED_DAEDALUS_AXE} to {@code DAEDALUS_AXE}). */
    private final Map<String, String> baseOfVariant;
    private final long lastUpdated;

    private MuseumCatalog(Map<String, Donation> byKey, Map<String, List<String>> keysForItem,
                          Map<String, String> baseOfVariant, long lastUpdated) {
        this.byKey = byKey;
        this.keysForItem = keysForItem;
        this.baseOfVariant = baseOfVariant;
        this.lastUpdated = lastUpdated;
    }

    /** The catalogue built from the most recent items resource; empty until one has loaded. */
    public static MuseumCatalog current() {
        return current;
    }

    public static void publish(MuseumCatalog catalog) {
        if (catalog != null) {
            current = catalog;
        }
    }

    public static MuseumCatalog parseResponse(String json) {
        ApiResponse response = new Gson().fromJson(json, ApiResponse.class);
        List<Input> inputs = new ArrayList<>();
        if (response != null && response.items != null) {
            for (ApiItem item : response.items) {
                if (item != null && item.id != null) {
                    inputs.add(new Input(item.id, item.name, Boolean.TRUE.equals(item.museum),
                            item.museum_data));
                }
            }
        }
        return build(inputs, response == null ? 0L : response.lastUpdated);
    }

    public static MuseumCatalog build(Iterable<Input> inputs, long lastUpdated) {
        Map<String, Donation> donations = new LinkedHashMap<>();
        Map<String, List<String>> keysForItem = new LinkedHashMap<>();
        Map<String, String> baseOfVariant = new LinkedHashMap<>();
        // Sets are assembled across their pieces before they become donations.
        Map<String, Integer> setXp = new LinkedHashMap<>();
        Map<String, Category> setCategory = new LinkedHashMap<>();
        Map<String, List<String>> setPieces = new LinkedHashMap<>();
        Map<String, String> higher = new LinkedHashMap<>();

        for (Input input : inputs) {
            String id = input.id().toUpperCase(Locale.ROOT);
            ApiMuseumData data = input.data();
            if (data == null) {
                if (input.museumFlag()) {
                    donations.put(id, new Donation(id, clean(input.name(), id), Category.SPECIAL, 0,
                            false, List.of(), null, null));
                    keysForItem.put(id, List.of(id));
                }
                continue;
            }
            Category category = Category.byLabel(data.category);
            if (category == null) {
                continue;   // a category this build does not know: not guessed into another one
            }
            Map<String, Integer> sets = data.armor_set_donation_xp;
            if (sets != null && !sets.isEmpty()) {
                List<String> keys = new ArrayList<>();
                for (Map.Entry<String, Integer> set : sets.entrySet()) {
                    String setId = set.getKey().toUpperCase(Locale.ROOT);
                    int xp = set.getValue() == null ? 0 : set.getValue();
                    // Four sets carry different XP on different pieces; the highest reproduces the
                    // resource's total. Which one the Museum pays is not verified (see the spec).
                    setXp.merge(setId, xp, Math::max);
                    setCategory.putIfAbsent(setId, category);
                    setPieces.computeIfAbsent(setId, k -> new ArrayList<>()).add(id);
                    keys.add(SET_PREFIX + setId);
                }
                keysForItem.put(id, List.copyOf(keys));
                if (data.parent != null) {
                    for (Map.Entry<String, String> link : data.parent.entrySet()) {
                        if (link.getValue() != null && !link.getValue().isEmpty()) {
                            higher.put(SET_PREFIX + link.getKey().toUpperCase(Locale.ROOT),
                                    SET_PREFIX + link.getValue().toUpperCase(Locale.ROOT));
                        }
                    }
                }
                continue;
            }
            int xp = data.donation_xp == null ? 0 : data.donation_xp;
            donations.put(id, new Donation(id, clean(input.name(), id), category, xp, false, List.of(),
                    null, null));
            keysForItem.put(id, List.of(id));
            if (data.parent != null) {
                String next = data.parent.get(input.id());
                if (next == null && data.parent.size() == 1) {
                    next = data.parent.values().iterator().next();
                }
                if (next != null && !next.isEmpty()) {
                    higher.put(id, next.toUpperCase(Locale.ROOT));
                }
            }
            if (data.mapped_item_ids != null) {
                for (String variant : data.mapped_item_ids) {
                    if (variant != null && !variant.isEmpty()) {
                        baseOfVariant.put(variant.toUpperCase(Locale.ROOT), id);
                    }
                }
            }
        }
        for (Map.Entry<String, Integer> set : setXp.entrySet()) {
            String key = SET_PREFIX + set.getKey();
            donations.put(key, new Donation(key, setName(set.getKey()), setCategory.get(set.getKey()),
                    set.getValue(), true, List.copyOf(setPieces.get(set.getKey())), null, null));
        }
        // Tier links, both ways, now that every donation exists.
        Map<String, String> lower = new LinkedHashMap<>();
        higher.forEach((lo, hi) -> {
            if (donations.containsKey(lo) && donations.containsKey(hi)) {
                lower.put(hi, lo);
            }
        });
        Map<String, Donation> linked = new LinkedHashMap<>();
        for (Donation donation : donations.values()) {
            String hi = higher.get(donation.key());
            linked.put(donation.key(), new Donation(donation.key(), donation.displayName(),
                    donation.category(), donation.xp(), donation.set(), donation.pieces(),
                    lower.get(donation.key()), hi != null && donations.containsKey(hi) ? hi : null));
        }
        return new MuseumCatalog(Collections.unmodifiableMap(linked),
                Collections.unmodifiableMap(keysForItem), Collections.unmodifiableMap(baseOfVariant),
                lastUpdated);
    }

    // ------------------------------------------------------------------ queries

    public boolean isEmpty() {
        return byKey.isEmpty();
    }

    public long lastUpdated() {
        return lastUpdated;
    }

    public Donation donation(String key) {
        return key == null ? null : byKey.get(key);
    }

    public Collection<Donation> all() {
        return byKey.values();
    }

    /**
     * The donation keys an item counts toward: its own, its set's (two for a shared piece), or - for
     * a variant - its base item's. Empty when the Museum does not take it.
     */
    public List<String> keysFor(String itemId) {
        if (itemId == null) {
            return List.of();
        }
        String id = itemId.toUpperCase(Locale.ROOT);
        List<String> keys = keysForItem.get(id);
        if (keys != null) {
            return keys;
        }
        String base = baseOfVariant.get(id);
        return base == null ? List.of() : keysForItem.getOrDefault(base, List.of());
    }

    /**
     * The donation a donated menu slot stands for. A set is shown by one piece (its helmet), named
     * {@code "<Set> Armor"}; for a piece in two sets the slot's name decides which one it is.
     */
    public String keyForMenuSlot(String itemId, String slotName) {
        List<String> keys = keysFor(itemId);
        if (keys.size() <= 1) {
            return keys.isEmpty() ? null : keys.get(0);
        }
        String name = slotName == null ? "" : slotName.trim();
        for (String key : keys) {
            Donation donation = byKey.get(key);
            if (donation != null && nameKey(donation.displayName()).equals(nameKey(name))) {
                return key;
            }
        }
        return null;   // ambiguous: better unrecorded than filed under the wrong set
    }

    /** Sum of every donation's XP, optionally for one category. */
    public int totalXp() {
        int total = 0;
        for (Donation donation : byKey.values()) {
            total += donation.xp();
        }
        return total;
    }

    public Map<Category, Integer> xpByCategory() {
        Map<Category, Integer> out = new EnumMap<>(Category.class);
        for (Donation donation : byKey.values()) {
            out.merge(donation.category(), donation.xp(), Integer::sum);
        }
        return out;
    }

    /** {@code CRIMSON_HUNTER} to {@code "Crimson Hunter Armor"}, the name the menu gives a set. */
    static String setName(String setId) {
        StringBuilder out = new StringBuilder();
        for (String word : setId.toLowerCase(Locale.ROOT).split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(' ');
        }
        String name = out.toString().trim();
        // Some set ids already end in the word ("GOLEM_ARMOR" is "Golem Armor", not "... Armor Armor").
        return name.endsWith(" Armor") || name.equals("Armor") ? name : name + " Armor";
    }

    /**
     * A set name reduced for comparison: letters only, a possessive dropped, "armor" dropped - the
     * menu says "Rosetta's Armor" where the id says {@code ROSETTA}.
     */
    static String nameKey(String name) {
        String lower = name.toLowerCase(Locale.ROOT).replace("'s ", " ").replace((char) 0x2019 + "s ", " ");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                out.append(c);
            }
        }
        String key = out.toString();
        return key.endsWith("armor") ? key.substring(0, key.length() - "armor".length()) : key;
    }

    /** The resource names some items with colour tokens ({@code %%red%%}); the id stands in for none. */
    private static String clean(String name, String id) {
        return name == null || name.isBlank() ? id : name.replaceAll("%%[a-z_]+%%", "").trim();
    }
}
