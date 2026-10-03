/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.itemvalue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * An item's gemstone slots: which it <b>has</b>, which are unlocked, and which hold a gem.
 *
 * <p>Two sources meet here, and neither answers the question alone.
 * <ul>
 *   <li><b>What the item has</b> comes from Hypixel's keyless items resource, {@code gemstone_slots}:
 *   one entry per slot, in lore order, with a {@code slot_type}, optional {@code costs} (coins and
 *   items) and optional {@code requirements}. A slot with no {@code costs} is open from the start.
 *   Parsed by {@link #parseDefinitions}.</li>
 *   <li><b>What is applied</b> comes from the item's own {@code gems} compound, read by
 *   {@link ItemModifiers#readGems} - the same reader the appraisal prices from, so the NBT is parsed
 *   once.</li>
 * </ul>
 *
 * <p><b>How a definition finds its NBT key.</b> Hypixel numbers slots per type in lore order:
 * the Divan's Drill's {@code AMBER, AMBER, JADE, JADE, MINING} are {@code AMBER_0, AMBER_1, JADE_0,
 * JADE_1, MINING_0}. Checked against every gem-carrying item in the maintainer's storage capture.
 *
 * <p><b>Unlocked is three facts, not one</b> (all seen in that capture):
 * <ul>
 *   <li>{@code unlocked_slots} lists only slots that <i>had</i> to be unlocked - the Titanium Drill's
 *   free {@code AMBER_0} holds a Perfect Amber and is absent from it;</li>
 *   <li>a legacy item can have no {@code unlocked_slots} at all while its slots hold gems (a Power
 *   Wither helmet), so a filled slot is unlocked by definition;</li>
 *   <li>the list can repeat a key ({@code ["COMBAT_0","COMBAT_1","COMBAT_1","COMBAT_0"]}), so it is a
 *   set, never a count.</li>
 * </ul>
 *
 * <p><b>Requirement-gated slots are hidden until met</b>, matching Hypixel's own lore: a Theoretical
 * Hoe at level 15 prints two of its three brackets. A gated slot that nevertheless holds a gem is
 * still shown - the gem is on the item and is counted in its value either way.
 */
public final class GemSlots {

    /** One slot as the items resource defines it. */
    public record SlotDef(String type, long coinCost, List<ItemCost> itemCosts, int requiredLevel) {

        /** Whether the slot is open without being unlocked (the resource lists no cost). */
        public boolean free() {
            return coinCost <= 0 && itemCosts.isEmpty();
        }
    }

    /** One item an unlock consumes. */
    public record ItemCost(String itemId, int amount) {
    }

    /** A gem sitting in a slot: its NBT key ({@code COMBAT_0}), grade and kind. */
    public record Filled(String slotKey, String quality, String gemType) {

        /** The Bazaar id this gem trades under, e.g. {@code PERFECT_JASPER_GEM}. */
        public String itemId() {
            return quality.toUpperCase(Locale.ROOT) + "_" + gemType.toUpperCase(Locale.ROOT) + "_GEM";
        }
    }

    /** What the item's {@code gems} compound says: the gems in it and the slots marked unlocked. */
    public record Applied(List<Filled> filled, Set<String> unlocked) {

        public static final Applied NONE = new Applied(List.of(), Set.of());

        public Filled gemIn(String slotKey) {
            for (Filled gem : filled) {
                if (gem.slotKey().equals(slotKey)) {
                    return gem;
                }
            }
            return null;
        }
    }

    /** What a slot shows as. */
    public enum State {
        FILLED, EMPTY, LOCKED
    }

    /** One resolved slot. {@code gem} is set exactly when the state is {@link State#FILLED}. */
    public record Slot(String key, String type, State state, Filled gem) {
    }

    private GemSlots() {
    }

    /**
     * Reads the resource's {@code gemstone_slots} array. Anything malformed in one entry drops that
     * entry rather than the item; a missing or non-array element is no slots at all.
     */
    public static List<SlotDef> parseDefinitions(JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        List<SlotDef> defs = new ArrayList<>();
        for (JsonElement entry : element.getAsJsonArray()) {
            if (!entry.isJsonObject()) {
                continue;
            }
            JsonObject slot = entry.getAsJsonObject();
            String type = string(slot, "slot_type");
            if (type == null || type.isBlank()) {
                continue;
            }
            long coins = 0;
            List<ItemCost> items = new ArrayList<>();
            if (slot.get("costs") instanceof JsonArray costs) {
                for (JsonElement costElement : costs) {
                    if (!costElement.isJsonObject()) {
                        continue;
                    }
                    JsonObject cost = costElement.getAsJsonObject();
                    String kind = string(cost, "type");
                    if ("COINS".equals(kind)) {
                        coins += number(cost, "coins");
                    } else if ("ITEM".equals(kind)) {
                        String itemId = string(cost, "item_id");
                        if (itemId != null) {
                            items.add(new ItemCost(itemId, (int) Math.max(1, number(cost, "amount"))));
                        }
                    }
                }
            }
            defs.add(new SlotDef(type.toUpperCase(Locale.ROOT), coins, List.copyOf(items), requiredLevel(slot)));
        }
        return List.copyOf(defs);
    }

    /**
     * The only requirement the resource carries today (108 slots, all of them) is
     * {@code ITEM_DATA levelable_lvl >= N}. Any other shape is not understood and counts as no
     * requirement, so the slot shows rather than silently disappearing.
     */
    private static int requiredLevel(JsonObject slot) {
        if (!(slot.get("requirements") instanceof JsonArray requirements)) {
            return 0;
        }
        int level = 0;
        for (JsonElement element : requirements) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject requirement = element.getAsJsonObject();
            if ("ITEM_DATA".equals(string(requirement, "type"))
                    && "levelable_lvl".equals(string(requirement, "data_key"))
                    && "GREATER_THAN_OR_EQUALS".equals(string(requirement, "operator"))) {
                try {
                    level = Math.max(level, Integer.parseInt(string(requirement, "value").trim()));
                } catch (RuntimeException malformed) {
                    // Unreadable threshold: treat as no requirement (see the method doc).
                }
            }
        }
        return level;
    }

    /**
     * The slots to show, in lore order.
     *
     * @param defs      the resource's slots for this item; empty when the catalogue has not loaded or
     *                  does not know the item, in which case only what the NBT itself proves is shown
     * @param applied   the item's {@code gems} compound, read
     * @param itemLevel the item's {@code levelable_lvl}, {@code 0} for an item that has none
     */
    public static List<Slot> resolve(List<SlotDef> defs, Applied applied, int itemLevel) {
        List<Slot> slots = new ArrayList<>();
        Set<String> placed = new LinkedHashSet<>();
        Map<String, Integer> perType = new HashMap<>();
        for (SlotDef def : defs) {
            int index = perType.merge(def.type(), 1, Integer::sum) - 1;
            String key = def.type() + "_" + index;
            Filled gem = applied.gemIn(key);
            if (gem != null) {
                slots.add(new Slot(key, def.type(), State.FILLED, gem));
                placed.add(key);
            } else if (def.requiredLevel() > itemLevel) {
                continue;   // Hypixel's lore hides it until the item is levelled that far
            } else if (def.free() || applied.unlocked().contains(key)) {
                slots.add(new Slot(key, def.type(), State.EMPTY, null));
                placed.add(key);
            } else {
                slots.add(new Slot(key, def.type(), State.LOCKED, null));
                placed.add(key);
            }
        }
        // What the NBT proves that the definitions do not know about: a stale or missing catalogue
        // must not hide a gem the item really carries, nor a slot the item says it unlocked.
        for (Filled gem : applied.filled()) {
            if (placed.add(gem.slotKey())) {
                slots.add(new Slot(gem.slotKey(), typeOf(gem.slotKey()), State.FILLED, gem));
            }
        }
        for (String key : applied.unlocked()) {
            if (placed.add(key)) {
                slots.add(new Slot(key, typeOf(key), State.EMPTY, null));
            }
        }
        return slots;
    }

    /** {@code COMBAT_0} to {@code COMBAT}. */
    static String typeOf(String slotKey) {
        int underscore = slotKey.lastIndexOf('_');
        return underscore > 0 ? slotKey.substring(0, underscore) : slotKey;
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static long number(JsonObject object, String key) {
        JsonElement value = object.get(key);
        try {
            return value != null && value.isJsonPrimitive() ? value.getAsLong() : 0;
        } catch (NumberFormatException malformed) {
            return 0;
        }
    }
}
