/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.wishlist.logic;

import sbs.modid.client.economy.wishlist.model.WishlistEntry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The text form of the list-valued wishlist criteria, both ways: what the editor's fields show and
 * how what the player typed becomes the stored entry. Pure, so the round trip is unit-tested.
 *
 * <p>Each list is comma separated and forgiving about case and spacing:
 * <ul>
 *   <li>enchants: {@code growth 6, protection 6, ultimate wise 5}</li>
 *   <li>gems: {@code 2x perfect jasper, flawless any}</li>
 *   <li>blacklist: {@code skin, dye:pure_black, reforge:wise, enchant:smite, gem:ruby}</li>
 * </ul>
 * A part that cannot be read is reported back by name rather than dropped silently, so the editor
 * can say which word it did not understand.
 */
public final class WishlistSpecText {

    private static final Pattern ENCHANT = Pattern.compile("^(.+?)\\s+(\\d{1,2})$");
    private static final Pattern GEM = Pattern.compile("^(?:(\\d{1,2})\\s*x?\\s+)?([a-z]+)\\s+([a-z]+)$");
    private static final Pattern SPACES = Pattern.compile("[\\s_]+");
    private static final Set<String> BLACKLIST_KINDS = Set.of("skin", "dye", "reforge", "enchant", "gem");

    private WishlistSpecText() {
    }

    /** A parsed value plus the parts that could not be read. */
    public record Parsed<T>(T value, List<String> errors) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    // ------------------------------------------------------------------
    // Enchants
    // ------------------------------------------------------------------

    public static Parsed<Map<String, Integer>> parseEnchants(String text) {
        Map<String, Integer> out = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        for (String part : split(text)) {
            Matcher m = ENCHANT.matcher(part);
            if (!m.matches()) {
                errors.add(part);
                continue;
            }
            int level = Integer.parseInt(m.group(2));
            if (level <= 0) {
                errors.add(part);
                continue;
            }
            out.put(SPACES.matcher(m.group(1).trim()).replaceAll("_"), level);
        }
        return new Parsed<>(out, errors);
    }

    public static String formatEnchants(Map<String, Integer> enchants) {
        List<String> parts = new ArrayList<>();
        if (enchants != null) {
            enchants.forEach((key, level) -> parts.add(key.replace('_', ' ') + " " + level));
        }
        return String.join(", ", parts);
    }

    // ------------------------------------------------------------------
    // Gems
    // ------------------------------------------------------------------

    public static Parsed<List<WishlistEntry.GemRequirement>> parseGems(String text) {
        List<WishlistEntry.GemRequirement> out = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (String part : split(text)) {
            Matcher m = GEM.matcher(part);
            if (!m.matches()) {
                errors.add(part);
                continue;
            }
            String quality = m.group(2).toUpperCase(Locale.ROOT);
            if (!WishlistMatcher.QUALITIES.contains(quality)) {
                errors.add(part);
                continue;
            }
            int count = m.group(1) == null ? 1 : Integer.parseInt(m.group(1));
            if (count <= 0) {
                errors.add(part);
                continue;
            }
            out.add(new WishlistEntry.GemRequirement(m.group(3).toUpperCase(Locale.ROOT), quality, count));
        }
        return new Parsed<>(out, errors);
    }

    public static String formatGems(List<WishlistEntry.GemRequirement> gems) {
        List<String> parts = new ArrayList<>();
        if (gems != null) {
            for (WishlistEntry.GemRequirement gem : gems) {
                String body = gem.minQuality.toLowerCase(Locale.ROOT) + " " + gem.type.toLowerCase(Locale.ROOT);
                parts.add(gem.count > 1 ? gem.count + "x " + body : body);
            }
        }
        return String.join(", ", parts);
    }

    // ------------------------------------------------------------------
    // Blacklist
    // ------------------------------------------------------------------

    /** Tokens are kept in the stored form {@code kind} or {@code kind:value}, value spaces as {@code _}. */
    public static Parsed<List<String>> parseBlacklist(String text) {
        List<String> out = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (String part : split(text)) {
            int colon = part.indexOf(':');
            String kind = (colon < 0 ? part : part.substring(0, colon)).trim();
            String value = colon < 0 ? "" : SPACES.matcher(part.substring(colon + 1).trim()).replaceAll("_");
            boolean bareAllowed = "skin".equals(kind) || "dye".equals(kind);
            if (!BLACKLIST_KINDS.contains(kind) || (value.isEmpty() && !bareAllowed)) {
                errors.add(part);
                continue;
            }
            out.add(value.isEmpty() ? kind : kind + ":" + value);
        }
        return new Parsed<>(out, errors);
    }

    public static String formatBlacklist(List<String> blacklist) {
        return blacklist == null ? "" : String.join(", ", blacklist);
    }

    // ------------------------------------------------------------------
    // Coins
    // ------------------------------------------------------------------

    /** "50m", "1.5b", "800k" or plain coins; {@code -1} when it cannot be read, {@code 0} when empty. */
    public static long parseCoins(String text) {
        String t = text == null ? "" : text.trim().toLowerCase(Locale.ROOT).replace(",", ".");
        if (t.isEmpty()) {
            return 0;
        }
        double mult = 1;
        char last = t.charAt(t.length() - 1);
        if (last == 'b' || last == 'm' || last == 'k') {
            mult = last == 'b' ? 1e9 : last == 'm' ? 1e6 : 1e3;
            t = t.substring(0, t.length() - 1).trim();
        }
        try {
            double value = Double.parseDouble(t) * mult;
            return value < 0 || Double.isNaN(value) || value > 1e15 ? -1 : Math.round(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The shortest exact form of {@code coins} that {@link #parseCoins} reads back unchanged. */
    public static String formatCoins(long coins) {
        if (coins <= 0) {
            return "";
        }
        if (coins >= 1_000_000_000L && coins % 10_000_000L == 0) {
            return trim(coins / 1e9) + "b";
        }
        if (coins >= 1_000_000L && coins % 10_000L == 0) {
            return trim(coins / 1e6) + "m";
        }
        if (coins >= 1_000L && coins % 10L == 0) {
            return trim(coins / 1e3) + "k";
        }
        return Long.toString(coins);
    }

    private static String trim(double value) {
        String text = String.format(Locale.ROOT, "%.2f", value);
        while (text.endsWith("0")) {
            text = text.substring(0, text.length() - 1);
        }
        return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
    }

    private static List<String> split(String text) {
        List<String> parts = new ArrayList<>();
        if (text == null) {
            return parts;
        }
        for (String raw : text.split("[,;]")) {
            String part = raw.trim().toLowerCase(Locale.ROOT);
            if (!part.isEmpty()) {
                parts.add(part);
            }
        }
        return parts;
    }
}
