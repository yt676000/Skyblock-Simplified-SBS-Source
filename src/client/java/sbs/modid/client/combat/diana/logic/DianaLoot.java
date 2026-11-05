/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.logic;

import sbs.modid.client.core.util.RareDropLine;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a Diana chat line paid the player: an item and how many, or coins. Pure - no Minecraft, no
 * config, no clock - so every confirmed line shape is a unit test.
 *
 * <h2>Shapes, from the maintainer's play-instance logs (to 2026-10)</h2>
 * <pre>
 * RARE DROP! You dug out a Griffin Feather!          burrow  (5,106 times)
 * RARE DROP! You dug out a Crown of Greed!           burrow  (also Mythos Fragment, Washed-up Souvenir)
 * Wow! You dug out 750,000 coins!                    burrow  (2,273 times)
 * RARE DROP! Daedalus Stick (+193% ✯ Magic Find)     mob     (also Antique Remedies, Dwarf Turtle
 * RARE DROP! Washed-up Souvenir (+MF)                         Shelmet, Crochet Tiger Plushie, Chimera)
 * </pre>
 * Minos Relic is in the table on the wiki's word - it does not appear in those logs, so its line is
 * assumed to have the same shape as the other mob drops. "Machine Gun Shortbow", which the request
 * listed, is not a Diana drop: every one of its 957 lines follows a Kill Combo line, not a creature.
 *
 * <h2>Only the player's own</h2>
 *
 * <p>Every pattern is anchored at the start of the line. A party or guild relay ("Party &gt; [MVP+]
 * Someone: Wow! You dug out 500,000 coins!") and another mod's re-post ("[SBO] RARE DROP! …") have
 * something in front of the sentence and never match - the logs hold hundreds of both.
 *
 * <h2>Mob drops are a closed list</h2>
 *
 * <p>A "RARE DROP!" line names any item in the game, and a player in the Hub can be killing anything.
 * So a mob drop counts only when the item is in {@link Item} (Washed-up Souvenir comes both ways). A
 * "You dug out" drop is different - only a burrow says that - so an unknown name there is still
 * counted, unpriced, which is how a new burrow item shows up in the table instead of disappearing.
 */
public final class DianaLoot {

    /** Where the coins or the item came from. */
    public enum Source {
        BURROW,
        MOB,
        COINS
    }

    /** The items the event is known to pay. The id is the Bazaar / auction key the price is read under. */
    public enum Item {
        GRIFFIN_FEATHER("Griffin Feather", "GRIFFIN_FEATHER", Source.BURROW),
        MYTHOS_FRAGMENT("Mythos Fragment", "MYTHOS_FRAGMENT", Source.BURROW),
        CROWN_OF_GREED("Crown of Greed", "CROWN_OF_GREED", Source.BURROW),
        WASHED_UP_SOUVENIR("Washed-up Souvenir", "WASHED_UP_SOUVENIR", Source.BURROW),
        /** An enchanted book; the Bazaar lists it under its enchantment key. */
        CHIMERA("Chimera I", "ENCHANTMENT_ULTIMATE_CHIMERA_1", Source.MOB),
        MINOS_RELIC("Minos Relic", "MINOS_RELIC", Source.MOB),
        DAEDALUS_STICK("Daedalus Stick", "DAEDALUS_STICK", Source.MOB),
        DWARF_TURTLE_SHELMET("Dwarf Turtle Shelmet", "DWARF_TURTLE_SHELMET", Source.MOB),
        ANTIQUE_REMEDIES("Antique Remedies", "ANTIQUE_REMEDIES", Source.MOB),
        CROCHET_TIGER_PLUSHIE("Crochet Tiger Plushie", "CROCHET_TIGER_PLUSHIE", Source.MOB);

        private final String displayName;
        private final String id;
        private final Source source;

        Item(String displayName, String id, Source source) {
            this.displayName = displayName;
            this.id = id;
            this.source = source;
        }

        public String displayName() {
            return displayName;
        }

        public String id() {
            return id;
        }

        public Source source() {
            return source;
        }
    }

    /**
     * One payout. {@code id} is the price key ({@link #COINS_ID} for coins), {@code count} the number
     * of items or of coins.
     */
    public record Loot(String id, String name, long count, Source source) {
    }

    /** The id coin payouts are booked under. */
    public static final String COINS_ID = "COINS";

    /** "Wow! You dug out 750,000 coins!" */
    private static final Pattern COINS = Pattern.compile("^Wow! You dug out ([\\d,.]+) coins!\\s*$");

    /** "RARE DROP! You dug out a Griffin Feather!" with an optional Magic Find suffix. */
    private static final Pattern DUG = Pattern.compile(
            "^RARE DROP! You dug out (?:an? )?(.+?)!(?:\\s+\\(\\+\\d+%?\\s*\\S*\\s*Magic Find\\))?\\s*$");

    /** "Enchanted Book (Chimera I)" - the shape the book may be named in. */
    private static final Pattern BOOK = Pattern.compile("^Enchanted Book \\((.+)\\)$");

    private static final Pattern NON_KEY = Pattern.compile("[^A-Z0-9_]");

    private static final Map<String, Item> BY_NAME = byName();

    private DianaLoot() {
    }

    /** The payout a colour-stripped chat line announces, or {@code null}. */
    public static Loot parse(String plain) {
        if (plain == null) {
            return null;
        }
        String text = plain.trim();
        Matcher coins = COINS.matcher(text);
        if (coins.matches()) {
            long amount = parseLong(coins.group(1));
            return amount > 0 ? new Loot(COINS_ID, "Coins", amount, Source.COINS) : null;
        }
        Matcher dug = DUG.matcher(text);
        if (dug.matches()) {
            String name = dug.group(1).trim();
            Item item = lookup(name);
            if (item != null) {
                return new Loot(item.id(), item.displayName(), 1, Source.BURROW);
            }
            return name.isEmpty() ? null : new Loot(key(name), name, 1, Source.BURROW);
        }
        RareDropLine.Drop drop = RareDropLine.parse(text);
        if (drop == null) {
            return null;
        }
        Item item = lookup(drop.item());
        if (item == null) {
            return null;
        }
        return new Loot(item.id(), item.displayName(), Math.max(1, drop.count()), Source.MOB);
    }

    /** The catalogue entry for a price id, or {@code null}. */
    public static Item byId(String id) {
        for (Item item : Item.values()) {
            if (item.id().equals(id)) {
                return item;
            }
        }
        return null;
    }

    /**
     * The catalogue entry for a name as a line wrote it. Tolerates the book being named as a book,
     * a trailing "!" and a trailing tier numeral ("Chimera", "Chimera I", "Enchanted Book (Chimera I)").
     */
    static Item lookup(String rawName) {
        String name = rawName.trim();
        Matcher book = BOOK.matcher(name);
        if (book.matches()) {
            name = book.group(1).trim();
        }
        while (name.endsWith("!")) {
            name = name.substring(0, name.length() - 1).trim();
        }
        String lower = name.toLowerCase(Locale.ROOT);
        Item item = BY_NAME.get(lower);
        if (item == null && lower.endsWith(" i")) {
            item = BY_NAME.get(lower.substring(0, lower.length() - 2));
        }
        return item;
    }

    private static Map<String, Item> byName() {
        Map<String, Item> out = new java.util.HashMap<>();
        for (Item item : Item.values()) {
            out.put(item.displayName().toLowerCase(Locale.ROOT), item);
        }
        out.put("chimera", Item.CHIMERA);
        return Map.copyOf(out);
    }

    /** "Four-Eyed Fish" -> "FOUR_EYED_FISH": an unknown burrow item's best-guess key. */
    static String key(String name) {
        return NON_KEY.matcher(name.toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_'))
                .replaceAll("");
    }

    private static long parseLong(String digits) {
        try {
            return Long.parseLong(digits.replace(",", "").replace(".", ""));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
