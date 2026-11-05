/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.item;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Everything the mod derives from a live stack's SkyBlock data, computed once and shared by every
 * feature that asks: id, instance uuid, rarity and the price lookup keys.
 *
 * <p><b>Keyed by component identity, not by stack.</b> Every component value ({@link CustomData},
 * {@link ItemLore}, a name {@link Component}) is immutable, and {@code ItemStack.set} or a slot
 * update always installs a <i>new</i> instance. An analysis is therefore stored under its
 * {@code CUSTOM_DATA} instance (or under its {@link Item} when there is none) and remembers the
 * other component instances it read; a lookup compares those by reference and recomputes on any
 * mismatch. A mutated stack can never be served a stale analysis, no per-slot invalidation is
 * needed, and copies of one stack - which share their component instances - share one analysis.
 *
 * <p>"No SkyBlock id" is an analysis like any other ({@link #id} is {@code null}), so a vanilla
 * pane is not re-read every frame either.
 *
 * <p><b>Bounded and cleared on transitions:</b> wiped wholesale past {@link #CAP} entries, on every
 * screen change and on world change ({@link #clear}).
 *
 * <p><b>Render/client thread only.</b> A call from any other thread (a background index) computes
 * an uncached analysis, so the maps are never touched off-thread and the answer is still correct.
 *
 * <p>Price keys read {@code getHoverName}, which the renamer and the Text Editor rewrite. Both are
 * edited from a screen, and the screen change clears this cache, so a new rename is picked up.
 */
public record ItemAnalysis(
        String id,
        String uuid,
        Rarity rarity,
        List<String> baseCandidates,
        List<String> priceKeys,
        String idForPricing) {

    /** Analysis of the empty stack: no id, no rarity, no keys. */
    public static final ItemAnalysis NONE = new ItemAnalysis(null, null, null, List.of(), List.of(), null);

    static final int CAP = 4096;

    private static final Map<Object, Entry> CACHE = new IdentityHashMap<>();
    private static Thread owner;
    private static long hits;
    private static long misses;
    /** Set while {@link #compute} runs on the owner thread; see {@link #of}. */
    private static boolean computing;

    /** One cached analysis plus the component instances it was computed from. */
    private record Entry(Object lore, Object customName, Object itemName, Item item, ItemAnalysis value) {
    }

    /** Whether this item carries a SkyBlock id. */
    public boolean isSkyblock() {
        return id != null;
    }

    /** The analysis for {@code stack}; {@link #NONE} for a null or empty stack. Never null. */
    public static ItemAnalysis of(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return NONE;
        }
        if (Thread.currentThread() != owner) {
            return compute(stack);
        }
        if (computing) {
            // A hook inside getHoverName asked for an analysis while one is being built. Answer
            // from the tag alone, so a future hook can never recurse into compute.
            return bare(stack);
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        ItemLore lore = stack.get(DataComponents.LORE);
        Component customName = stack.get(DataComponents.CUSTOM_NAME);
        Component itemName = stack.get(DataComponents.ITEM_NAME);
        Item item = stack.getItem();
        Object key = data != null ? data : item;
        Entry entry = CACHE.get(key);
        if (entry != null && entry.lore == lore && entry.customName == customName
                && entry.itemName == itemName && entry.item == item) {
            hits++;
            return entry.value;
        }
        misses++;
        if (entry == null && CACHE.size() >= CAP) {
            CACHE.clear();
        }
        ItemAnalysis value;
        computing = true;
        try {
            value = compute(stack);
        } finally {
            computing = false;
        }
        CACHE.put(key, new Entry(lore, customName, itemName, item, value));
        return value;
    }

    /** Computes an analysis without touching the cache. */
    static ItemAnalysis compute(ItemStack stack) {
        CompoundTag extra = SkyblockItem.extraAttributes(stack);
        String lowerKeyId = nonEmpty(extra.getStringOr("id", ""));
        String id = SkyblockItem.readId(extra);
        String uuid = nonEmpty(extra.getStringOr("uuid", ""));
        if (uuid == null) {
            uuid = nonEmpty(extra.getStringOr("UUID", ""));
        }
        String hoverName = stack.getHoverName().getString();
        String registryPath = id == null
                ? BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().toUpperCase(Locale.ROOT)
                : null;

        LinkedHashSet<String> base = new LinkedHashSet<>();
        if (id != null) {
            base.add(id.toUpperCase(Locale.ROOT));
        }
        String normalized = SkyblockItem.normalizeName(hoverName);
        if (!normalized.isEmpty()) {
            base.add(normalized);
        }
        if (registryPath != null) {
            base.add(registryPath);
        }

        LinkedHashSet<String> keys = new LinkedHashSet<>();
        // Only the lower-case "id" key selects a composed form, as the price lookup always did.
        String composed = composedKey(lowerKeyId, extra, hoverName);
        if (composed != null) {
            keys.add(composed);
        }
        keys.addAll(base);

        return new ItemAnalysis(id, uuid, Rarity.detectUncached(stack), List.copyOf(base),
                List.copyOf(keys), id != null ? id : registryPath);
    }

    /** Id and uuid only, uncached - the re-entrant answer. Never reads the display name. */
    private static ItemAnalysis bare(ItemStack stack) {
        CompoundTag extra = SkyblockItem.extraAttributes(stack);
        String uuid = nonEmpty(extra.getStringOr("uuid", ""));
        if (uuid == null) {
            uuid = nonEmpty(extra.getStringOr("UUID", ""));
        }
        return new ItemAnalysis(SkyblockItem.readId(extra), uuid, null, List.of(), List.of(), null);
    }

    /**
     * The market id the API keys a pet, enchanted book or rune under, which the bare item id
     * ({@code PET}, {@code ENCHANTED_BOOK}) does not identify: {@code PET_ENDER_DRAGON},
     * {@code ENCHANTED_BOOK_ULTIMATE_LEGION_5}, {@code RUNE_MUSIC_3}. {@code null} for anything else.
     */
    private static String composedKey(String id, CompoundTag extra, String hoverName) {
        if (id == null) {
            return null;
        }
        switch (id.toUpperCase(Locale.ROOT)) {
            case "PET" -> {
                String type = petType(extra);
                if (type == null || type.isEmpty()) {
                    type = SkyblockItem.normalizeName(hoverName);
                }
                return type.isEmpty() ? null : "PET_" + type.toUpperCase(Locale.ROOT);
            }
            case "ENCHANTED_BOOK" -> {
                return singleEntryId(extra.getCompoundOrEmpty("enchantments"), "ENCHANTED_BOOK_");
            }
            case "RUNE", "UNIQUE_RUNE" -> {
                return singleEntryId(extra.getCompoundOrEmpty("runes"), "RUNE_");
            }
            default -> {
                return null;
            }
        }
    }

    /** The {@code "type"} of the {@code petInfo} JSON string ("ENDER_DRAGON"), or {@code null}. */
    private static String petType(CompoundTag extra) {
        String petInfo = extra.getStringOr("petInfo", "");
        if (petInfo.isEmpty()) {
            return null;
        }
        try {
            JsonObject info = JsonParser.parseString(petInfo).getAsJsonObject();
            return info.has("type") ? info.get("type").getAsString() : null;
        } catch (RuntimeException e) {
            return null; // malformed petInfo: the display name is the fallback
        }
    }

    /**
     * Composes {@code prefix + NAME + "_" + level} from a one-entry compound like
     * {@code enchantments: {ultimate_legion: 5}} / {@code runes: {MUSIC: 3}}, or {@code null}.
     */
    private static String singleEntryId(CompoundTag compound, String prefix) {
        for (String key : compound.keySet()) {
            int level = compound.getIntOr(key, 0);
            if (level > 0) {
                return prefix + key.toUpperCase(Locale.ROOT) + "_" + level;
            }
        }
        return null;
    }

    private static String nonEmpty(String value) {
        return value.isEmpty() ? null : value;
    }

    /** Binds the cache to the calling thread; called once from the client initializer. */
    public static void bindThread() {
        owner = Thread.currentThread();
    }

    /** Drops every analysis - on screen change and world change. Render/client thread only. */
    public static void clear() {
        CACHE.clear();
    }

    /** Lookups answered from the cache since start. */
    public static long hits() {
        return hits;
    }

    /** Lookups that had to compute since start. */
    public static long misses() {
        return misses;
    }

    static int size() {
        return CACHE.size();
    }
}
