/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.trade.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Everything about one offered item that decides what it is worth - the fingerprint Trade Guard
 * compares when a slot changes or a partner offers something they showed before.
 *
 * <p>Plain data, no Minecraft types, so the whole comparison is unit-testable. Built from a live
 * stack by {@code TradeReader}; built by hand in tests through {@link #builder}.
 *
 * @param skyblockId  ExtraAttributes id, {@code null} for a vanilla item
 * @param uuid        ExtraAttributes uuid, {@code null} for stackables
 * @param material    vanilla item path ({@code "iron_sword"})
 * @param name        display name, plain text, no formatting codes
 * @param coins       the lump-sum total of a coin offer head, {@code -1} for every other item
 * @param stars       dungeon stars 0-5
 * @param masterStars master stars 0-5 (upgrade levels above 5)
 * @param enchants    enchant id to level, sorted
 * @param gems        gem slot to quality/type, sorted
 * @param scrolls     ability scrolls, sorted
 * @param pet         pet data, {@code null} for anything that is not a pet
 * @param other       every other scalar attribute (drill fuel, drill parts, art of war ...), sorted
 */
public record ItemPrint(String skyblockId, String uuid, String material, String name, int count,
                        long coins, int stars, int masterStars, boolean recomb, String reforge,
                        int hotPotato, Map<String, Integer> enchants, Map<String, String> gems,
                        List<String> scrolls, Pet pet, String dye, String skin,
                        Map<String, String> other) {

    /** Pet data from {@code petInfo}; level is read from the "[Lvl N]" in the name. */
    public record Pet(String type, String tier, double exp, int level, String heldItem, String skin) {
    }

    public ItemPrint {
        enchants = enchants == null ? Map.of() : Map.copyOf(new java.util.TreeMap<>(enchants));
        gems = gems == null ? Map.of() : Map.copyOf(new java.util.TreeMap<>(gems));
        scrolls = scrolls == null ? List.of() : scrolls.stream().sorted().toList();
        other = other == null ? Map.of() : Map.copyOf(new java.util.TreeMap<>(other));
        name = name == null ? "" : name;
        material = material == null ? "" : material;
    }

    public boolean isCoins() {
        return coins >= 0;
    }

    /** Same thing, attribute for attribute - what "unchanged" means to the guard. */
    public boolean sameAs(ItemPrint o) {
        return o != null && equals(o);
    }

    /** Uuid when there is one, otherwise the whole print: what "the same item" means across slots. */
    public Object identity() {
        return uuid != null && !uuid.isEmpty() ? "uuid:" + uuid : this;
    }

    public static Builder builder(String skyblockId, String material, String name) {
        return new Builder(skyblockId, material, name);
    }

    /** Test and reader convenience; every field defaults to "absent". */
    public static final class Builder {
        private final String skyblockId;
        private final String material;
        private final String name;
        private String uuid;
        private int count = 1;
        private long coins = -1;
        private int stars;
        private int masterStars;
        private boolean recomb;
        private String reforge;
        private int hotPotato;
        private Map<String, Integer> enchants = new java.util.TreeMap<>();
        private Map<String, String> gems = new java.util.TreeMap<>();
        private List<String> scrolls = new java.util.ArrayList<>();
        private Pet pet;
        private String dye;
        private String skin;
        private Map<String, String> other = new java.util.TreeMap<>();

        private Builder(String skyblockId, String material, String name) {
            this.skyblockId = skyblockId;
            this.material = material;
            this.name = name;
        }

        public Builder uuid(String v) { uuid = v; return this; }
        public Builder count(int v) { count = v; return this; }
        public Builder coins(long v) { coins = v; return this; }
        public Builder stars(int v) { stars = v; return this; }
        public Builder masterStars(int v) { masterStars = v; return this; }
        public Builder recomb(boolean v) { recomb = v; return this; }
        public Builder reforge(String v) { reforge = v; return this; }
        public Builder hotPotato(int v) { hotPotato = v; return this; }
        public Builder enchant(String id, int level) { enchants.put(id, level); return this; }
        public Builder gem(String slot, String value) { gems.put(slot, value); return this; }
        public Builder scroll(String v) { scrolls.add(v); return this; }
        public Builder pet(Pet v) { pet = v; return this; }
        public Builder dye(String v) { dye = v; return this; }
        public Builder skin(String v) { skin = v; return this; }
        public Builder other(String key, String value) { other.put(key, value); return this; }

        public ItemPrint build() {
            return new ItemPrint(skyblockId, uuid, material, name, count, coins, stars, masterStars,
                    recomb, reforge, hotPotato, enchants, gems, scrolls, pet, dye, skin, other);
        }
    }

    @Override
    public String toString() {
        return name + (skyblockId == null ? "" : " [" + skyblockId + "]") + (count > 1 ? " x" + count : "")
                + (isCoins() ? " (" + coins + " coins)" : "") + (uuid == null ? "" : " " + uuid)
                + (Objects.equals(reforge, null) ? "" : " {" + reforge + "}");
    }
}
