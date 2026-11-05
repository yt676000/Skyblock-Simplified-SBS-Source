/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.recipe.logic;

import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import sbs.modid.client.core.item.SkyblockItem;

/**
 * The pure half of the R-key recipe lookup: how a hovered item's display name becomes a search
 * query when its SkyBlock id has no recipe, and how the hotkey's own character is kept out of the
 * viewer's search box. No Minecraft types, so both are unit-tested.
 */
public final class RecipeLookup {

    private static final Pattern FORMATTING_CODE = Pattern.compile(String.valueOf((char) 0x00A7) + ".");
    private static final Pattern PET_LEVEL_PREFIX = Pattern.compile("(?i)^\\[Lvl\\s*[0-9]+]\\s*");
    /**
     * Upgrade and decoration glyphs: dungeon stars, master stars (➊-➎), crimson stars with a count
     * ("✪10"), and the symbols Hypixel appends for recombobulated, shiny or cosmetic items.
     */
    private static final Pattern DECORATIONS = Pattern.compile("[✪➊➋➌➍➎⚚✦✿◆★☘⚂]+[0-9]*");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private RecipeLookup() {
    }

    /**
     * The base item name of a decorated display name: "Fabled Flaming Flay ✪✪✪✪✪" → "Flaming Flay",
     * "[Lvl 100] Golden Dragon" → "Golden Dragon". The leading reforge word comes from the shared
     * {@link SkyblockItem#isReforgeWord} list and is kept when the whole name is itself a known item
     * ({@code isKnownName}), so "Strong Dragon Helmet" stays intact.
     */
    public static String cleanName(String displayName, Predicate<String> isKnownName) {
        if (displayName == null) {
            return "";
        }
        String clean = FORMATTING_CODE.matcher(displayName).replaceAll("");
        clean = PET_LEVEL_PREFIX.matcher(clean.trim()).replaceAll("");
        clean = DECORATIONS.matcher(clean).replaceAll("");
        clean = SPACES.matcher(clean).replaceAll(" ").trim();
        if (isKnownName != null && isKnownName.test(clean)) {
            return clean;
        }
        String[] words = clean.split(" ");
        // Two-word reforges ("Green Thumb") first, then one word; never strip the whole name.
        if (words.length > 2 && SkyblockItem.isReforgeWord(reforgeKey(words[0] + "_" + words[1]))) {
            return clean.substring(words[0].length() + words[1].length() + 2);
        }
        if (words.length > 1 && SkyblockItem.isReforgeWord(reforgeKey(words[0]))) {
            return clean.substring(words[0].length() + 1);
        }
        return clean;
    }

    /**
     * The id to open the viewer on directly, or {@code null} for the text-search fallback: only an
     * id that has something to show ({@code hasEntries}: recipes, or usages for Shift+R) qualifies.
     */
    public static String directId(String skyblockId, Predicate<String> hasEntries) {
        if (skyblockId == null || skyblockId.isEmpty()) {
            return null;
        }
        return hasEntries.test(skyblockId) ? skyblockId : null;
    }

    /**
     * The character a bound key types, for {@link CharSwallow#arm}: letters, digits and printable
     * ASCII keys (GLFW codes equal their upper-case ASCII), 0 for anything that types nothing.
     */
    public static char hotkeyChar(int glfwKey) {
        return glfwKey >= 32 && glfwKey <= 126 ? Character.toLowerCase((char) glfwKey) : 0;
    }

    private static String reforgeKey(String word) {
        return word.toUpperCase(Locale.ROOT);
    }

    /**
     * Keeps the hotkey's character out of the newly opened viewer. The key press is consumed by the
     * container, but the character event that follows it is delivered to the next screen, whose
     * search box is focused. Armed with the hotkey's char, it swallows exactly that one character;
     * any key press disarms it, so a later real 'r' is always typed.
     */
    public static final class CharSwallow {

        private int pending;

        public void arm(int codepoint) {
            this.pending = codepoint;
        }

        /** Called on every key press: the hotkey's character can only follow its own key press. */
        public void disarm() {
            this.pending = 0;
        }

        /** True when {@code codepoint} is the hotkey's character and must not be typed. */
        public boolean swallow(int codepoint) {
            int armed = pending;
            pending = 0;
            return armed != 0 && Character.toLowerCase(codepoint) == Character.toLowerCase(armed);
        }
    }
}
