/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.trade.logic;

import sbs.modid.client.economy.trade.model.ItemPrint;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Groups items that look alike to a human: "Titanium Drill DR-X655" and "DR-X355", Hyperion and
 * Astraea, Necron's and Storm's chestplate, a pet at level 1 and at level 100.
 *
 * <p>The key is either a hand-kept alias family or the display name with formatting, stars, pet
 * levels and any word containing a digit removed, plus the material. The alias list is bundled;
 * {@link #setAliases} replaces it, which is the seam for loading it from the backend later.
 */
public final class ItemFamily {

    /** A family: an item belongs when its normalised name contains one keyword and every {@code required} word. */
    public record Alias(String family, List<String> keywords, List<String> required) {
    }

    public static final List<Alias> BUNDLED = List.of(
            new Alias("drill", List.of("mithril", "titanium", "ruby", "gemstone", "topaz", "jasper",
                    "divan"), List.of("drill")),
            new Alias("wither blade", List.of("hyperion", "astraea", "valkyrie", "scylla", "necron's blade"),
                    List.of()),
            new Alias("wither helmet", List.of("necron", "storm", "goldor", "maxor", "wither"), List.of("helmet")),
            new Alias("wither chestplate", List.of("necron", "storm", "goldor", "maxor", "wither"),
                    List.of("chestplate")),
            new Alias("wither leggings", List.of("necron", "storm", "goldor", "maxor", "wither"),
                    List.of("leggings")),
            new Alias("wither boots", List.of("necron", "storm", "goldor", "maxor", "wither"), List.of("boots")),
            new Alias("terminator", List.of("terminator", "juju shortbow"), List.of()),
            new Alias("gauntlet", List.of("gemstone gauntlet", "divan's"), List.of("gauntlet")));

    private static final Pattern CODES = Pattern.compile("§.");
    private static final Pattern PET_LEVEL = Pattern.compile("\\[lvl \\d+]");
    private static final Pattern GLYPHS = Pattern.compile("[✪➊➋➌➍➎⚚✦◆❤❂☘✿]");
    private static final Pattern DIGIT_WORD = Pattern.compile("\\S*\\d\\S*");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private static volatile List<Alias> aliases = BUNDLED;

    private ItemFamily() {
    }

    public static void setAliases(List<Alias> list) {
        aliases = list == null || list.isEmpty() ? BUNDLED : List.copyOf(list);
    }

    /** The family key of an item. Pets group by type, whatever the level or rarity. */
    public static String of(ItemPrint print) {
        if (print == null) {
            return "";
        }
        if (print.isCoins()) {
            return "coins";
        }
        if (print.pet() != null && print.pet().type() != null) {
            return "pet:" + print.pet().type().toLowerCase(Locale.ROOT);
        }
        return of(print.name(), print.material());
    }

    public static String of(String name, String material) {
        String n = normalize(name);
        for (Alias a : aliases) {
            if (a.required().stream().allMatch(n::contains) && a.keywords().stream().anyMatch(n::contains)) {
                return a.family();
            }
        }
        return n + "|" + (material == null ? "" : material);
    }

    /** Lower-case, no formatting codes, stars, pet levels or words containing a digit. */
    public static String normalize(String name) {
        if (name == null) {
            return "";
        }
        String n = CODES.matcher(name).replaceAll("").toLowerCase(Locale.ROOT);
        n = PET_LEVEL.matcher(n).replaceAll(" ");
        n = GLYPHS.matcher(n).replaceAll(" ");
        n = DIGIT_WORD.matcher(n).replaceAll(" ");
        return SPACES.matcher(n).replaceAll(" ").trim();
    }

    /**
     * Whether two items would pass for each other at a glance: same family, or same material and
     * a name at most a few characters apart (a reforge prefix, a tier code).
     */
    public static boolean lookAlike(ItemPrint a, ItemPrint b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.isCoins() || b.isCoins()) {
            return a.isCoins() && b.isCoins();
        }
        if (of(a).equals(of(b))) {
            return true;
        }
        if (!a.material().equals(b.material())) {
            return false;
        }
        String na = CODES.matcher(a.name()).replaceAll("").toLowerCase(Locale.ROOT);
        String nb = CODES.matcher(b.name()).replaceAll("").toLowerCase(Locale.ROOT);
        if (na.equals(nb)) {
            return true;
        }
        int max = Math.max(na.length(), nb.length());
        if (max > 0 && distance(na, nb) <= Math.max(2, max / 3)) {
            return true;
        }
        // "Heroic Hyperion" vs "Hyperion": a reforge prefix is not a different item to the eye.
        String ca = normalize(na);
        String cb = normalize(nb);
        return !ca.isEmpty() && !cb.isEmpty() && (ca.endsWith(cb) || cb.endsWith(ca));
    }

    /** Levenshtein distance; names are short. */
    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
