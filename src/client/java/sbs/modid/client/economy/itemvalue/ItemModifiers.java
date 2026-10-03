/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.itemvalue;

import com.google.gson.JsonObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.economy.pricehistory.logic.PriceLookup;
import sbs.modid.client.economy.recipe.logic.SkyBlockRepoRecipeProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads everything of value that is applied to a SkyBlock item from its {@code ExtraAttributes}:
 * stars (converted to essence via the essence-cost table; master stars as their star items),
 * hot/fuming potato books, every enchantment (priced as books, tier-composable), gemstones,
 * reforge stones, runes, recombobulator, Art of War / Peace, ability (wither) scrolls, dyes,
 * skins, etherwarp, tuners and the other known appliable modifiers.
 *
 * <p>The output is a flat list of {@link Part}s – "this component of the item corresponds to
 * N × this market item id" – which {@link ItemValueService} prices asynchronously.
 */
public final class ItemModifiers {

    /** One value component of an item. Enchantments carry key+tier for tier-composition pricing. */
    public static final class Part {
        public final String label;
        public final String itemId;
        public final double count;
        public final String enchantKey;
        public final int enchantTier;

        private Part(String label, String itemId, double count, String enchantKey, int enchantTier) {
            this.label = label;
            this.itemId = itemId;
            this.count = count;
            this.enchantKey = enchantKey;
            this.enchantTier = enchantTier;
        }

        static Part item(String label, String itemId, double count) {
            return new Part(label, itemId, count, null, 0);
        }

        static Part book(String label, String enchantKey, int tier) {
            return new Part(label, null, 1, enchantKey, tier);
        }

        /** A component we recognized but cannot price (e.g. stars without essence data). */
        static Part unknown(String label) {
            return new Part(label, null, 1, null, 0);
        }

        public boolean isBook() {
            return enchantKey != null;
        }
    }

    /** Reforges applied via reforge stones: modifier name → the stone's item id. */
    private static final Map<String, String> REFORGE_STONES = new HashMap<>();

    static {
        REFORGE_STONES.put("ancient", "PRECURSOR_GEAR");
        REFORGE_STONES.put("withered", "WITHER_BLOOD");
        REFORGE_STONES.put("fabled", "DRAGON_CLAW");
        REFORGE_STONES.put("renowned", "DRAGON_HORN");
        REFORGE_STONES.put("spiked", "DRAGON_SCALE");
        REFORGE_STONES.put("jaded", "JADERALD");
        REFORGE_STONES.put("giant", "GIANT_TOOTH");
        REFORGE_STONES.put("submerged", "DEEP_SEA_ORB");
        REFORGE_STONES.put("suspicious", "SUSPICIOUS_VIAL");
        REFORGE_STONES.put("gilded", "MIDAS_JEWEL");
        REFORGE_STONES.put("warped", "WARPED_STONE");
        REFORGE_STONES.put("bustling", "SKYMART_BROCHURE");
        REFORGE_STONES.put("mossy", "OVERGROWN_GRASS");
        REFORGE_STONES.put("festive", "FROZEN_BAUBLE");
        REFORGE_STONES.put("snowy", "TERRY_SNOWGLOBE");
        REFORGE_STONES.put("toil", "TOIL_LOG");
        REFORGE_STONES.put("blooming", "FLOWERING_BOUQUET");
        REFORGE_STONES.put("rooted", "BURROWING_SPORES");
        REFORGE_STONES.put("blood_soaked", "PRESUMED_GALLON_OF_RED_PAINT");
        REFORGE_STONES.put("salty", "SALT_CUBE");
        REFORGE_STONES.put("treacherous", "RUSTY_ANCHOR");
        REFORGE_STONES.put("lucky", "LUCKY_DICE");
        REFORGE_STONES.put("stiff", "HARDENED_WOOD");
        REFORGE_STONES.put("dirty", "DIRT_BOTTLE");
        REFORGE_STONES.put("chomp", "KUUDRA_MANDIBLE");
        REFORGE_STONES.put("pitchin", "PITCHIN_KOI");
        REFORGE_STONES.put("ambered", "AMBER_MATERIAL");
        REFORGE_STONES.put("auspicious", "ROCK_GEMSTONE");
        REFORGE_STONES.put("fleet", "DIAMONITE");
        REFORGE_STONES.put("heated", "HOT_STUFF");
        REFORGE_STONES.put("magnetic", "LAPIS_CRYSTAL");
        REFORGE_STONES.put("mithraic", "PURE_MITHRIL");
        REFORGE_STONES.put("refined", "REFINED_AMBER");
        REFORGE_STONES.put("stellar", "PETRIFIED_STARFALL");
        REFORGE_STONES.put("coldfused", "ENTROPY_SUPPRESSOR");
        REFORGE_STONES.put("dimensional", "TITANIUM_TESSERACT");
        REFORGE_STONES.put("empowered", "SADAN_BROOCH");
        REFORGE_STONES.put("glistening", "SHINY_PRISM");
        REFORGE_STONES.put("hyper", "ENDSTONE_GEODE");
        REFORGE_STONES.put("moonglade", "MOONGLADE_JEWEL");
        REFORGE_STONES.put("royal", "DWARVEN_TREASURE");
        REFORGE_STONES.put("squeaky", "SQUEAKY_TOY");
        REFORGE_STONES.put("strengthened", "SEARING_STONE");
        REFORGE_STONES.put("waxed", "BLAZE_WAX");
        REFORGE_STONES.put("fortified", "METEOR_SHARD");
        REFORGE_STONES.put("earthy", "LARGE_WALNUT");
        REFORGE_STONES.put("greater_spook", "BOO_STONE");
        REFORGE_STONES.put("jerry_stone", "JERRY_STONE");
        REFORGE_STONES.put("undead", "PREMIUM_FLESH");
        REFORGE_STONES.put("necrotic", "NECROMANCER_BROOCH");
        REFORGE_STONES.put("loving", "RED_SCARF");
        REFORGE_STONES.put("ridiculous", "RED_NOSE");
        REFORGE_STONES.put("bulky", "BULKY_STONE");
        REFORGE_STONES.put("perfect", "DIAMOND_ATOM");
        REFORGE_STONES.put("headstrong", "SALMON_OPAL");
        REFORGE_STONES.put("precise", "OPTICAL_LENS");
        REFORGE_STONES.put("spiritual", "SPIRIT_STONE");
        REFORGE_STONES.put("candied", "CANDY_CORN");
    }

    private ItemModifiers() {
    }

    /** Every value component of the stack, base item first. Empty for non-items. */
    public static List<Part> parse(ItemStack stack) {
        List<Part> parts = new ArrayList<>();
        if (stack == null || stack.isEmpty()) {
            return parts;
        }
        List<String> candidates = PriceLookup.candidatesFor(stack);
        String baseId = candidates.isEmpty() ? null : candidates.get(0);
        String baseName = stack.getHoverName().getString()
                .replaceAll(String.valueOf((char) 0x00A7) + ".", "").trim();
        if (baseId != null) {
            parts.add(Part.item(baseName + " (base)", baseId, 1));
        }
        parts.addAll(parseModifiers(stack, baseId));
        return parts;
    }

    /**
     * Everything applied <b>onto</b> the item – stars, books, gems, reforge stone, runes and the
     * rest – without the item itself.
     *
     * <p>Split out from {@link #parse} for callers that price the base item their own way (through
     * the full candidate list rather than just the first id, say) and only need the extras on top,
     * so nothing has to guess which of the returned parts was the base one.
     *
     * @param baseId the item's own market id, which the star reader needs to find its essence
     *               costs; pass {@code null} when unknown (stars then read as unpriced). Taken as
     *               a parameter because every caller has already resolved it.
     */
    public static List<Part> parseModifiers(ItemStack stack, String baseId) {
        List<Part> parts = new ArrayList<>();
        if (stack == null || stack.isEmpty()) {
            return parts;
        }
        CompoundTag extra = extraAttributes(stack);
        if (extra.isEmpty()) {
            return parts;
        }

        parseStars(parts, extra, baseId);
        parsePotatoBooks(parts, extra);
        parseSimpleCounts(parts, extra);
        parseScrolls(parts, extra);
        parseEnchantments(parts, extra);
        parseGems(parts, extra);
        parseReforge(parts, extra);
        parseRunes(parts, extra);
        parseStrings(parts, extra);
        return parts;
    }

    // ------------------------------------------------------------------
    // Individual readers
    // ------------------------------------------------------------------

    /**
     * Stars: tiers 1–5 convert to essence via the essence-cost table; tiers 6–10 are master
     * stars (their star items) – unless the essence table prices those tiers too (Kuudra gear
     * upgrades entirely with essence).
     */
    private static void parseStars(List<Part> parts, CompoundTag extra, String baseId) {
        int stars = Math.max(extra.getIntOr("upgrade_level", 0), extra.getIntOr("dungeon_item_level", 0));
        if (stars <= 0) {
            return;
        }
        JsonObject costs = SkyBlockRepoRecipeProvider.getInstance().essenceCosts();
        JsonObject entry = costs != null && baseId != null && costs.has(baseId)
                ? costs.getAsJsonObject(baseId) : null;
        if (entry == null) {
            parts.add(Part.unknown("Stars x" + stars));
            return;
        }
        String type = entry.has("type") ? entry.get("type").getAsString() : null;
        boolean essenceBeyondFive = entry.has("6");
        long essence = 0;
        int essenceTiers = 0;
        int limit = essenceBeyondFive ? Math.min(stars, 10) : Math.min(stars, 5);
        for (int tier = 1; tier <= limit; tier++) {
            String key = String.valueOf(tier);
            if (entry.has(key)) {
                essence += entry.get(key).getAsLong();
                essenceTiers++;
            }
        }
        if (type != null && essence > 0) {
            parts.add(Part.item(essenceTiers + " Star" + (essenceTiers == 1 ? "" : "s")
                            + " (" + type + " Essence x" + essence + ")",
                    "ESSENCE_" + type.toUpperCase(Locale.ROOT), essence));
        }
        if (!essenceBeyondFive && stars > 5) {
            String[] masterStars = {"FIRST_MASTER_STAR", "SECOND_MASTER_STAR", "THIRD_MASTER_STAR",
                    "FOURTH_MASTER_STAR", "FIFTH_MASTER_STAR"};
            int count = Math.min(stars - 5, 5);
            for (int i = 0; i < count; i++) {
                parts.add(Part.item(prettify(masterStars[i]), masterStars[i], 1));
            }
        }
    }

    private static void parsePotatoBooks(List<Part> parts, CompoundTag extra) {
        int potato = extra.getIntOr("hot_potato_count", 0);
        if (potato <= 0) {
            return;
        }
        int hot = Math.min(potato, 10);
        parts.add(Part.item("Hot Potato Book x" + hot, "HOT_POTATO_BOOK", hot));
        if (potato > 10) {
            parts.add(Part.item("Fuming Potato Book x" + (potato - 10), "FUMING_POTATO_BOOK", potato - 10));
        }
    }

    /** Counted single-item modifiers stored as plain ints. */
    private static void parseSimpleCounts(List<Part> parts, CompoundTag extra) {
        addCounted(parts, extra, "rarity_upgrades", "Recombobulator 3000", "RECOMBOBULATOR_3000");
        addCounted(parts, extra, "art_of_war_count", "The Art of War", "THE_ART_OF_WAR");
        addCounted(parts, extra, "artOfPeaceApplied", "The Art of Peace", "THE_ART_OF_PEACE");
        addCounted(parts, extra, "wood_singularity_count", "Wood Singularity", "WOOD_SINGULARITY");
        addCounted(parts, extra, "farming_for_dummies_count", "Farming for Dummies", "FARMING_FOR_DUMMIES");
        // Book of Stats applies exactly once; its NBT field is the running KILL counter, not a
        // count – reading it as a count multiplied one book by hundreds of thousands (the 5.5kb bug).
        addFlag(parts, extra, "stats_book", "Book of Stats", "BOOK_OF_STATS");
        addFlag(parts, extra, "jalapeno_count", "Jalapeno Book", "JALAPENO_BOOK");
        addCounted(parts, extra, "mana_disintegrator_count", "Mana Disintegrator", "MANA_DISINTEGRATOR");
        addCounted(parts, extra, "polarvoid", "Polarvoid Book", "POLARVOID_BOOK");
        addCounted(parts, extra, "bookworm_books", "Bookworm's Favorite Book", "BOOKWORM_BOOK");
        addCounted(parts, extra, "tuned_transmission", "Transmission Tuner", "TRANSMISSION_TUNER");
        addCounted(parts, extra, "divan_powder_coating", "Divan's Powder Coating", "DIVAN_POWDER_COATING");
        if (extra.getIntOr("ethermerge", 0) > 0) {
            parts.add(Part.item("Etherwarp Conduit", "ETHERWARP_CONDUIT", 1));
            parts.add(Part.item("Etherwarp Merger", "ETHERWARP_MERGER", 1));
        }
    }

    private static void addCounted(List<Part> parts, CompoundTag extra, String key, String label, String itemId) {
        int count = extra.getIntOr(key, 0);
        if (count > 0) {
            parts.add(Part.item(count > 1 ? label + " x" + count : label, itemId, count));
        }
    }

    /**
     * A single-application upgrade whose NBT field is a presence/progress value, not a stack count:
     * present (&gt; 0) means exactly one was applied, so it prices as one item regardless of the
     * stored number (e.g. Book of Stats, whose field is a kill counter).
     */
    private static void addFlag(List<Part> parts, CompoundTag extra, String key, String label, String itemId) {
        if (extra.getIntOr(key, 0) > 0) {
            parts.add(Part.item(label, itemId, 1));
        }
    }

    /** Ability scrolls (Necron blade wither scrolls etc.) – the list values ARE item ids. */
    private static void parseScrolls(List<Part> parts, CompoundTag extra) {
        try {
            ListTag scrolls = extra.getListOrEmpty("ability_scroll");
            for (int i = 0; i < scrolls.size(); i++) {
                String id = scrolls.get(i).toString().replace("\"", "").trim();
                if (!id.isEmpty()) {
                    parts.add(Part.item(prettify(id), id.toUpperCase(Locale.ROOT), 1));
                }
            }
        } catch (Exception ignored) {
            // Malformed scroll list – skip rather than break the whole value check.
        }
        String powerScroll = extra.getStringOr("power_ability_scroll", "");
        if (!powerScroll.isEmpty()) {
            parts.add(Part.item(prettify(powerScroll), powerScroll.toUpperCase(Locale.ROOT), 1));
        }
    }

    /** Every enchantment becomes a book part; Efficiency VI+ additionally counts Silex applies. */
    private static void parseEnchantments(List<Part> parts, CompoundTag extra) {
        CompoundTag enchants = extra.getCompoundOrEmpty("enchantments");
        for (String key : enchants.keySet()) {
            int level = enchants.getIntOr(key, 0);
            if (level <= 0) {
                continue;
            }
            if ("efficiency".equals(key) && level > 5) {
                parts.add(Part.item("Silex x" + (level - 5), "SIL_EX", level - 5));
                level = 5;
            }
            parts.add(Part.book(prettify(key) + " " + level, key, level));
        }
    }

    /** Gemstones: one part per applied gem, read through {@link #readGems}. */
    private static void parseGems(List<Part> parts, CompoundTag extra) {
        for (GemSlots.Filled gem : readGems(extra).filled()) {
            String id = gem.itemId();
            parts.add(Part.item(prettify(id), id, 1));
        }
    }

    /**
     * The item's {@code gems} compound: every applied gem and the slots marked unlocked. The one
     * reader of it - the appraisal prices from {@link GemSlots.Applied#filled()} and the Gemstone
     * slot summary shows all of it.
     *
     * <p>Shapes seen in real items: a slot maps to its quality as a string ({@code JASPER_0:"FINE"})
     * or to a compound ({@code AMBER_0:{quality:"PERFECT",uuid:"..."}}); a typed slot names its gem in
     * {@code <slot>_gem} ({@code COMBAT_0_gem:"ONYX"}); {@code unlocked_slots} is a list of slot keys.
     */
    public static GemSlots.Applied readGems(CompoundTag extra) {
        CompoundTag gems = extra.getCompoundOrEmpty("gems");
        if (gems.isEmpty()) {
            return GemSlots.Applied.NONE;
        }
        List<GemSlots.Filled> filled = new ArrayList<>();
        for (String key : gems.keySet()) {
            if (key.endsWith("_gem") || "unlocked_slots".equals(key)) {
                continue;
            }
            String quality = gems.getStringOr(key, "");
            if (quality.isEmpty()) {
                quality = gems.getCompoundOrEmpty(key).getStringOr("quality", "");
            }
            if (quality.isEmpty()) {
                continue;
            }
            String gemType = gems.getStringOr(key + "_gem", "");
            if (gemType.isEmpty()) {
                gemType = GemSlots.typeOf(key);
            }
            filled.add(new GemSlots.Filled(key, quality, gemType));
        }
        // keySet() is hash-ordered; sort so the summary line reads the same on every frame and
        // every client. The appraisal only sums the parts, so their order never mattered to it.
        filled.sort(java.util.Comparator.comparing(GemSlots.Filled::slotKey));
        java.util.Set<String> unlocked = new java.util.LinkedHashSet<>();
        ListTag list = gems.getListOrEmpty("unlocked_slots");
        for (int i = 0; i < list.size(); i++) {
            String key = list.getStringOr(i, "");
            if (!key.isEmpty()) {
                unlocked.add(key);
            }
        }
        return new GemSlots.Applied(List.copyOf(filled), java.util.Collections.unmodifiableSet(unlocked));
    }

    /** Reforge: only stone-applied reforges carry a market item; blacksmith ones are negligible. */
    private static void parseReforge(List<Part> parts, CompoundTag extra) {
        String modifier = extra.getStringOr("modifier", "");
        if (modifier.isEmpty()) {
            return;
        }
        String stone = REFORGE_STONES.get(modifier.toLowerCase(Locale.ROOT));
        if (stone != null) {
            parts.add(Part.item("Reforge: " + prettify(modifier) + " (" + prettify(stone) + ")", stone, 1));
        }
    }

    private static void parseRunes(List<Part> parts, CompoundTag extra) {
        CompoundTag runes = extra.getCompoundOrEmpty("runes");
        for (String key : runes.keySet()) {
            int tier = runes.getIntOr(key, 0);
            if (tier > 0) {
                String id = "RUNE_" + key.toUpperCase(Locale.ROOT) + "_" + tier;
                parts.add(Part.item(prettify(key) + " Rune " + tier, id, 1));
            }
        }
    }

    /** String-valued modifiers whose value is the applied item's id (dye, skin, drill parts). */
    private static void parseStrings(List<Part> parts, CompoundTag extra) {
        addStringItem(parts, extra, "dye_item", "Dye: ");
        addStringItem(parts, extra, "skin", "Skin: ");
        addStringItem(parts, extra, "drill_part_engine", "Drill Engine: ");
        addStringItem(parts, extra, "drill_part_fuel_tank", "Fuel Tank: ");
        addStringItem(parts, extra, "drill_part_upgrade_module", "Upgrade Module: ");
    }

    private static void addStringItem(List<Part> parts, CompoundTag extra, String key, String prefix) {
        String id = extra.getStringOr(key, "");
        if (!id.isEmpty()) {
            parts.add(Part.item(prefix + prettify(id), id.toUpperCase(Locale.ROOT), 1));
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Delegates to the shared reader, which also understands the modern flattened custom data. */
    private static CompoundTag extraAttributes(ItemStack stack) {
        return sbs.modid.client.core.item.SkyblockItem.extraAttributes(stack);
    }

    /** "ultimate_chimera" / "IMPLOSION_SCROLL" → "Ultimate Chimera" / "Implosion Scroll". */
    static String prettify(String id) {
        String[] words = id.toLowerCase(Locale.ROOT).split("[_\\s]+");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }
}
