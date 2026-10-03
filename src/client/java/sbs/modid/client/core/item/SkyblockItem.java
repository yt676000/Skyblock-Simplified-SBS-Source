/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.item;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import sbs.modid.SkyblockSimplifiedSBS;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Helpers for reading the Hypixel SkyBlock internal item id from both a live {@link ItemStack} and
 * from an auction's gzipped {@code item_bytes} NBT blob.
 *
 * <p>SkyBlock stores its id under {@code ExtraAttributes.id} in the item's custom NBT – on a live
 * stack that is the {@code minecraft:custom_data} component; in the auctions API it is inside the
 * base64 + gzip {@code item_bytes} (an NBT compound with an {@code "i"} list of items, each with a
 * {@code "tag"} compound). Uses only {@code net.minecraft.*} NBT classes – no Fabric helpers.
 */
public final class SkyblockItem {

    private static final String EXTRA_ATTRIBUTES = "ExtraAttributes";
    private static final String ID = "id";

    private SkyblockItem() {
    }

    /**
     * The SkyBlock attribute compound of a live stack, never {@code null}: the legacy nested
     * {@code ExtraAttributes} compound when present (any casing), otherwise the custom-data tag
     * <b>itself</b> – component-era Hypixel flattened the attributes into the top level of
     * {@code minecraft:custom_data} ({@code id}, {@code enchantments}, ... sit directly on the
     * tag; verified against the live client NBT). Empty when the stack has
     * no custom data at all.
     */
    public static CompoundTag extraAttributes(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return new CompoundTag();
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || data.isEmpty()) {
            return new CompoundTag();
        }
        CompoundTag tag = data.copyTag();
        CompoundTag extra = tag.getCompoundOrEmpty(EXTRA_ATTRIBUTES);
        if (!extra.isEmpty()) {
            return extra;
        }
        // Tolerate casing variants of the legacy key ("extraAttributes", ...).
        for (String key : tag.keySet()) {
            if (key.equalsIgnoreCase(EXTRA_ATTRIBUTES)) {
                CompoundTag cased = tag.getCompoundOrEmpty(key);
                if (!cased.isEmpty()) {
                    return cased;
                }
            }
        }
        return tag; // modern flattened form
    }

    /** The SkyBlock id of a live stack (e.g. "HYPERION"), or {@code null} if it is not a SkyBlock item. */
    public static String id(ItemStack stack) {
        CompoundTag extra = extraAttributes(stack);
        String id = extra.getStringOr(ID, "");
        if (id.isEmpty()) {
            id = extra.getStringOr("ID", "");
        }
        return id.isEmpty() ? null : id;
    }

    /**
     * The SkyBlock <b>instance</b> id of a live stack – {@code ExtraAttributes.uuid} – or
     * {@code null} for a stackable item, which has none.
     *
     * <p>This is the only stable identity a single physical item has. It survives reforging,
     * enchanting, stat changes, gemstone sockets, and moving between inventory, storage, the auction
     * house and the museum; the display name and the item id survive none of those reliably. Item
     * Protection keys on it for exactly that reason.
     *
     * <p>Kept here, beside {@link #id}, because this class is the single place the mod reads
     * {@code ExtraAttributes} – a MC version bump that moves the custom-data component is then one
     * file to change rather than a search across every feature that reads NBT.
     */
    public static String uuid(ItemStack stack) {
        CompoundTag extra = extraAttributes(stack);
        String uuid = extra.getStringOr("uuid", "");
        if (uuid.isEmpty()) {
            uuid = extra.getStringOr("UUID", "");
        }
        return uuid.isEmpty() ? null : uuid;
    }

    /**
     * Ordered, de-duplicated lookup keys for price matching, most specific first:
     * <ol>
     *   <li>the SkyBlock id from {@code ExtraAttributes.id},</li>
     *   <li>the formatting-stripped display name normalised to id convention (reforge prefix and
     *       upgrade stars removed – "Sharp Aspect of the End ✪✪" → {@code ASPECT_OF_THE_END}),</li>
     *   <li>the vanilla registry path ({@code STICK}) – <b>only for items without a SkyBlock id</b>:
     *       a custom item (e.g. Infiniboom on a TNT base) must never fall back to its vanilla base
     *       item, or it would show that item's prices and crafting cost.</li>
     * </ol>
     * Price consumers try each candidate against their cache until one hits.
     */
    public static List<String> priceLookupCandidates(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        String skyblockId = id(stack);
        if (skyblockId != null) {
            candidates.add(skyblockId.toUpperCase(Locale.ROOT));
        }
        String normalized = normalizeName(stack.getHoverName().getString());
        if (!normalized.isEmpty()) {
            candidates.add(normalized);
        }
        if (skyblockId == null) {
            candidates.add(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().toUpperCase(Locale.ROOT));
        }
        return new ArrayList<>(candidates);
    }

    /**
     * A stable per-item identity for the persistent renamer: the SkyBlock uuid when present
     * (unique items), else the SkyBlock id, else the item's <b>original</b> name (read straight
     * from the CUSTOM_NAME component so the renamer's own display hook cannot interfere) – which
     * makes every item renameable even when its ExtraAttributes cannot be read.
     */
    public static String uniqueKey(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        CompoundTag extra = extraAttributes(stack);
        String instanceId = uuid(stack);
        if (instanceId != null) {
            return "uuid:" + instanceId;
        }
        String id = extra.getStringOr(ID, "");
        if (!id.isEmpty()) {
            return "id:" + id;
        }
        // Component-level fallback: the raw custom name (bypasses the getHoverName display hook),
        // else the vanilla registry path. Renames then apply to all identically-named items.
        net.minecraft.network.chat.Component customName = stack.get(DataComponents.CUSTOM_NAME);
        String name = customName != null ? normalizeName(customName.getString()) : "";
        if (!name.isEmpty()) {
            return "name:" + name;
        }
        return "name:" + BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().toUpperCase(Locale.ROOT);
    }

    /**
     * Reforge prefixes (weapons, armor, tools, bows, equipment). Stripped from the FIRST word of a
     * display name before normalising, so "Sharp Aspect of the End" and a clean "Aspect of the End"
     * produce the same lookup key. Applied to both sides of the AH match (auction {@code item_name}
     * and hovered item), so reforged and clean listings meet in the middle.
     */
    private static final java.util.Set<String> REFORGES = java.util.Set.of(
            // Weapons
            "GENTLE", "ODD", "FAST", "FAIR", "EPIC", "SHARP", "HEROIC", "SPICY", "LEGENDARY", "FABLED",
            "SUSPICIOUS", "GILDED", "WARPED", "WITHERED", "BULKY", "FANGED", "DIRTY",
            // Armor / equipment
            "CLEAN", "FIERCE", "HEAVY", "LIGHT", "MYTHIC", "PURE", "TITANIC", "SMART", "WISE", "PERFECT",
            "NECROTIC", "ANCIENT", "UNDEAD", "LOVING", "RIDICULOUS", "EMPOWERED", "GIANT", "SUBMERGED",
            "RENOWNED", "SPIKED", "JADED", "CUBIC", "REINFORCED", "BUSTLING", "MOSSY", "FESTIVE",
            // Tools
            "AUSPICIOUS", "FLEET", "HEATED", "MAGNETIC", "MITHRAIC", "REFINED", "STELLAR", "FRUITFUL",
            "BLESSED", "EARTHY", "ROBUST", "ZOOMING", "PEASANT", "GREEN_THUMB", "BLOOMING",
            // Bows
            "AWKWARD", "DEADLY", "FINE", "GRAND", "HASTY", "NEAT", "RAPID", "RICH", "UNREAL", "PRECISE",
            "SPIRITUAL", "HEADSTRONG",
            // Misc / accessories
            "BIZARRE", "ITCHY", "OMINOUS", "PLEASANT", "PRETTY", "SHINY", "SIMPLE", "STRANGE", "VIVID",
            "GODLY", "DEMONIC", "FORCEFUL", "HURTFUL", "KEEN", "STRONG", "SUPERIOR", "UNPLEASANT",
            "ZEALOUS", "SILKY", "SWEET", "BLOODY", "SHADED");

    /** True when the given upper-cased word is a known reforge prefix ("SHARP", "GREEN_THUMB"). */
    public static boolean isReforgeWord(String word) {
        return word != null && REFORGES.contains(word);
    }

    /**
     * Normalises a display name to a stable lookup key: strips formatting codes, the variable
     * pet-level prefix ("[Lvl 63] Ender Dragon" → "ENDER_DRAGON"), a leading reforge prefix and
     * upgrade-star glyphs, then upper-cases and collapses everything non-alphanumeric to
     * underscores. Used for both sides of the AH match.
     */
    public static String normalizeName(String displayName) {
        if (displayName == null) {
            return "";
        }
        String clean = displayName.replaceAll(String.valueOf((char) 0x00A7) + ".", "").trim();
        clean = clean.replaceAll("(?i)^\\[Lvl\\s*[0-9]+]\\s*", ""); // pet levels vary per auction
        String key = clean.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
        // Strip one leading reforge word ("SHARP_ASPECT_OF_THE_END" → "ASPECT_OF_THE_END").
        int underscore = key.indexOf('_');
        if (underscore > 0 && REFORGES.contains(key.substring(0, underscore))) {
            key = key.substring(underscore + 1);
        }
        return key;
    }

    /**
     * The id to use for price lookups, which must resolve for <b>every</b> item – Hyperion or Stick.
     * Prefers the SkyBlock {@code ExtraAttributes.id}; for plain items that lack it (e.g. a vanilla
     * Stick), it falls back to the vanilla registry path upper-cased (e.g. {@code minecraft:stick} →
     * {@code STICK}), which is exactly how the Bazaar / auctions APIs key those items.
     */
    public static String idForPricing(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        String skyblockId = id(stack);
        if (skyblockId != null) {
            return skyblockId;
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().toUpperCase(Locale.ROOT);
    }

    /** The SkyBlock id inside a base64 + gzip auction {@code item_bytes} blob, or {@code null} on failure. */
    public static String idFromItemBytes(String itemBytes) {
        if (itemBytes == null || itemBytes.isEmpty()) {
            return null;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(itemBytes);
            CompoundTag root = NbtIo.readCompressed(new ByteArrayInputStream(raw), NbtAccounter.unlimitedHeap());
            ListTag items = root.getListOrEmpty("i");
            if (items.isEmpty()) {
                return null;
            }
            CompoundTag tag = items.getCompoundOrEmpty(0).getCompoundOrEmpty("tag");
            CompoundTag extra = tag.getCompoundOrEmpty(EXTRA_ATTRIBUTES);
            String id = extra.getStringOr(ID, "");
            return id.isEmpty() ? null : id;
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.debug("[SBS][LBIN] Failed to decode item_bytes: {}", e.toString());
            return null;
        }
    }
}
