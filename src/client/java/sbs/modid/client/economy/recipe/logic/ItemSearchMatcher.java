/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.recipe.logic;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.core.util.PlainText;
import sbs.modid.client.helper.enchants.EnchantNames;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The matcher behind Search Highlight Mode: does <i>this</i> stack answer the typed query.
 *
 * <p>Split out of {@link SearchHighlightState}, which owns the live query text and nothing else,
 * because the matching rules are the part with behaviour worth reading on its own.
 *
 * <h2>What is searched</h2>
 * The item's name and SkyBlock id, and - unless the player turns tooltips off - every lore line
 * as well. Searching the name alone is what made the feature look broken: on an auction the thing
 * you want to filter by is almost never in the name. "Which of these has Sharpness on it" is a
 * question about the tooltip, and the tooltip was the one thing the matcher never read.
 *
 * <h2>Enchantments, which are the reason this exists</h2>
 * Hypixel prints enchant levels as roman numerals ("Sharpness VI"), so a lore scan alone still
 * fails the moment the level is typed as a digit - which everybody does. The levels are also on
 * the item as data, in {@code ExtraAttributes.enchantments}, so the searchable text gets every
 * spelling of every enchant baked into it: {@code sharpness}, {@code sharpness 6},
 * {@code sharpness6} and {@code sharpness vi} all lead to the same item.
 *
 * <p>The names come from {@link EnchantNames#displayCandidates}, so an enchant whose lore name is
 * nothing like its id is still findable under both - {@code ultimate_reiterate} shows as "Duplex"
 * in game, and typing either word finds it.
 *
 * <h2>Terms, and why a number sticks to the word before it</h2>
 * The query is split on spaces and <b>every</b> term has to match (searching
 * {@code hyperion sharpness} means both, not the literal string). A term that is a level - digits
 * or a roman numeral - is glued onto the word in front of it, so {@code sharpness 6} stays one
 * term. Without that rule the {@code 6} would drift off and match any lore line with a 6 in it,
 * which is the opposite of what someone typing a level is asking for.
 *
 * <h2>Cost</h2>
 * This runs for every visible slot of every frame, so the text is built once per stack and cached
 * by identity ({@link ItemStack} inherits {@code equals}, and a slot is handed a <i>new</i> stack
 * whenever the server changes it - so an entry can never go stale). The cache is dropped whole
 * when it grows past {@link #CACHE_LIMIT}; menus turn their stacks over constantly and none of it
 * is worth keeping.
 */
public final class ItemSearchMatcher {

    private static final String[] NO_TERMS = new String[0];

    /** Roman numerals 1-10, the only levels SkyBlock enchants are ever shown at. */
    private static final String[] ROMAN = {
            "", "i", "ii", "iii", "iv", "v", "vi", "vii", "viii", "ix", "x"};

    private static final int CACHE_LIMIT = 1024;

    private static final Map<ItemStack, Searchable> CACHE = new ConcurrentHashMap<>();

    /** An item's searchable text, kept in the two halves the tooltip setting switches between. */
    private record Searchable(String name, String lore) {
    }

    private ItemSearchMatcher() {
    }

    /**
     * Splits a query into the terms that must <b>all</b> match, lower-cased, with each level glued
     * to the word it belongs to ({@code "hyperion sharpness 6"} into {@code [hyperion, sharpness 6]}).
     */
    public static String[] terms(String query) {
        if (query == null || query.isBlank()) {
            return NO_TERMS;
        }
        List<String> terms = new ArrayList<>();
        boolean canGlue = false; // the last term is a bare word, so a level may still attach to it
        for (String token : query.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            if (token.isEmpty()) {
                continue;
            }
            boolean isLevel = levelOf(token) > 0;
            if (isLevel && canGlue) {
                terms.set(terms.size() - 1, terms.get(terms.size() - 1) + " " + token);
                canGlue = false;
            } else {
                terms.add(token);
                canGlue = !isLevel;
            }
        }
        return terms.toArray(new String[0]);
    }

    /** True when every term is present in the stack's searchable text. */
    public static boolean matches(ItemStack stack, String[] terms, boolean searchTooltip) {
        if (terms == null || terms.length == 0 || stack == null || stack.isEmpty()) {
            return false;
        }
        Searchable text = searchable(stack);
        for (String term : terms) {
            if (!text.name().contains(term) && !(searchTooltip && text.lore().contains(term))) {
                return false;
            }
        }
        return true;
    }

    /** The level a token stands for - {@code "6"} and {@code "vi"} are both 6 - or 0 for a word. */
    private static int levelOf(String token) {
        if (token.isEmpty()) {
            return 0;
        }
        if (token.chars().allMatch(Character::isDigit)) {
            try {
                return Integer.parseInt(token);
            } catch (NumberFormatException ignored) {
                return 0; // a number too long to be a level is just text
            }
        }
        for (int level = 1; level < ROMAN.length; level++) {
            if (ROMAN[level].equals(token)) {
                return level;
            }
        }
        return 0;
    }

    private static Searchable searchable(ItemStack stack) {
        Searchable cached = CACHE.get(stack);
        if (cached != null) {
            return cached;
        }
        if (CACHE.size() >= CACHE_LIMIT) {
            CACHE.clear();
        }
        Searchable built = build(stack);
        CACHE.put(stack, built);
        return built;
    }

    private static Searchable build(ItemStack stack) {
        StringBuilder name = new StringBuilder();
        append(name, PlainText.strip(stack.getHoverName().getString()));
        String id = SkyblockItem.id(stack);
        if (id != null) {
            append(name, id);
            append(name, id.replace('_', ' ')); // so "aspect of the end" finds ASPECT_OF_THE_END
        }

        StringBuilder lore = new StringBuilder();
        ItemLore component = stack.get(DataComponents.LORE);
        if (component != null) {
            for (Component line : component.lines()) {
                append(lore, PlainText.strip(line.getString()));
            }
        }
        appendEnchants(lore, stack);

        return new Searchable(name.toString().toLowerCase(Locale.ROOT),
                lore.toString().toLowerCase(Locale.ROOT));
    }

    /**
     * Adds every spelling of every enchant on the item, so the level can be typed the way it is
     * read ("sharpness vi") or the way it is thought ("sharpness 6").
     */
    private static void appendEnchants(StringBuilder out, ItemStack stack) {
        CompoundTag enchants = SkyblockItem.extraAttributes(stack).getCompoundOrEmpty("enchantments");
        if (enchants.isEmpty()) {
            return;
        }
        for (String key : enchants.keySet()) {
            int level = enchants.getIntOr(key, 0);
            for (String candidate : EnchantNames.displayCandidates(key)) {
                if (candidate.isEmpty()) {
                    continue;
                }
                append(out, candidate);
                if (level <= 0) {
                    continue;
                }
                append(out, candidate + " " + level);
                append(out, candidate + level); // "sharpness6", typed without the space
                if (level < ROMAN.length) {
                    append(out, candidate + " " + ROMAN[level]);
                }
            }
        }
    }

    /**
     * Appends one searchable line. The newline matters: it stops a term from matching across the
     * join between two unrelated lines, which would highlight items nobody asked for.
     */
    private static void append(StringBuilder out, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (out.length() > 0) {
            out.append('\n');
        }
        out.append(text);
    }
}
