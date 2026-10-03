/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.hoppity.logic;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What Hoppity's Hunt says in chat, and which of today's eggs are up.
 *
 * <h2>The lines, taken from real logs rather than guessed</h2>
 * Every pattern here was read out of the maintainer's own {@code latest.log} and the archived logs
 * beside it (2026-07 through 2026-09-23), not from a wiki and not from memory. The exact strings,
 * colour codes stripped:
 *
 * <pre>
 *   HOPPITY'S HUNT A Chocolate Breakfast Egg has appeared!     (also Brunch, Lunch,
 *                                                               Déjeuner, Dinner, Supper)
 *   HOPPITY'S HUNT You found a Chocolate Lunch Egg!
 *   HOPPITY'S HUNT You found a Chocolate Brunch Egg on the crane!
 *   HOPPITY'S HUNT You found Bertha (COMMON)!
 *   Hoppity's Hunt has begun! Help Hoppity find his Chocolate Rabbit Eggs across
 *   SkyBlock each day during the Spring!
 * </pre>
 *
 * All six meals are CONFIRMED to occur - Breakfast, Brunch, Lunch, Déjeuner, Dinner, Supper - and
 * the begin line is what settles the season question: the hunt runs <b>during the Spring</b>, in
 * Hypixel's own words. The tab widget carries {@code Event: Hoppity's Hunt, Ends In: 29h} while it
 * is on, which is a better source for "is it running" than any clock arithmetic.
 *
 * <p>"You found &lt;Name&gt; (RARITY)!" is a <b>rabbit</b>, not an egg's location - it is what the
 * egg contained. It is parsed because it looks almost exactly like the egg line and would otherwise
 * be mistaken for one.
 *
 * <h2>The trap, and why the sender guard is not optional</h2>
 * This line is in the logs too:
 *
 * <pre>
 *   [493] [MVP+] Julian_HD_xx: HOPPITY'S HUNT You found Ferb (MYTHIC)!
 * </pre>
 *
 * Another player typed the announcement into public chat. A {@code contains("You found")} match
 * counts that as <i>your</i> collection, and anything learning from it - a spot catalogue above all -
 * records a position that has nothing to do with any egg. So a line only counts as the player's own
 * when it has <b>no sender prefix</b>: the same rule the chat waypoints already live by, for the
 * same reason: chat text is written by other players and is treated as untrusted input.
 */
public final class HoppityChat {

    /** The six meals Hypixel names, in the order they occur through a SkyBlock day. CONFIRMED. */
    public enum Meal {
        BREAKFAST("Breakfast"),
        BRUNCH("Brunch"),
        LUNCH("Lunch"),
        DEJEUNER("Déjeuner"),
        DINNER("Dinner"),
        SUPPER("Supper");

        private final String label;

        Meal(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** The meal this text names, or {@code null}. Accent-insensitive for Déjeuner's sake. */
        public static Meal of(String text) {
            if (text == null) {
                return null;
            }
            String lower = text.toLowerCase(Locale.ROOT);
            for (Meal meal : values()) {
                if (lower.contains(meal.label.toLowerCase(Locale.ROOT))
                        || (meal == DEJEUNER && lower.contains("dejeuner"))) {
                    return meal;
                }
            }
            return null;
        }
    }

    /** What a parsed line turned out to be. */
    public enum Kind {
        /** An egg spawned somewhere on the island. */
        APPEARED,
        /** The player collected an egg. The only line a spot catalogue may learn from. */
        FOUND_EGG,
        /** The player got a rabbit out of an egg - not a location. */
        FOUND_RABBIT,
        /** The hunt started. */
        BEGUN
    }

    /** One understood line. {@code meal} is null for a rabbit; {@code name} is null for an egg. */
    public record Event(Kind kind, Meal meal, String name, String rarity) {
    }

    /**
     * The prefix, without colour codes. Hypixel writes it bold pink; the shared listener hands this
     * class text that has already been stripped.
     */
    private static final String PREFIX = "HOPPITY'S HUNT";

    private static final Pattern APPEARED =
            Pattern.compile("A Chocolate (.+?) Egg has appeared!");

    /**
     * "You found a Chocolate Lunch Egg!" and "… Egg on the crane!" - the suffix naming where it was
     * is optional and is not captured. It describes a landmark, not a coordinate, and the position
     * the catalogue wants is the player's own at that moment.
     */
    private static final Pattern FOUND_EGG =
            Pattern.compile("You found a Chocolate (.+?) Egg\\b");

    private static final Pattern FOUND_RABBIT =
            Pattern.compile("You found ([A-Za-z0-9_' -]{1,32}) \\((COMMON|UNCOMMON|RARE|EPIC|LEGENDARY|MYTHIC|DIVINE)\\)!");

    /**
     * A sender prefix: anything before a colon that is not part of the announcement itself.
     *
     * <p>Hypixel's own Hoppity lines have no colon at all before the text, so the test is simply
     * whether a colon appears ahead of the prefix. Ranks, guild tags and SkyBlock levels all sit in
     * that space, which is why the pattern does not try to enumerate them.
     */
    private static boolean fromAnotherPlayer(String stripped, int prefixAt) {
        return stripped.lastIndexOf(':', prefixAt) >= 0;
    }

    /** Which meals have appeared today and not yet been collected. Insertion-ordered. */
    private final Map<Meal, Long> open = new LinkedHashMap<>();

    private volatile boolean huntRunning;

    private static final HoppityChat INSTANCE = new HoppityChat();

    public HoppityChat() {
    }

    public static HoppityChat getInstance() {
        return INSTANCE;
    }

    /**
     * Understands one chat line, or returns {@code null}.
     *
     * <p>Static and side-effect free so it can be tested without a game: {@link #onChat} is the part
     * that keeps state.
     *
     * @param stripped the line with colour codes already removed
     */
    public static Event parse(String stripped) {
        if (stripped == null || stripped.isBlank()) {
            return null;
        }
        if (stripped.contains("Hoppity's Hunt has begun")) {
            return fromAnotherPlayer(stripped, stripped.indexOf("Hoppity's Hunt"))
                    ? null : new Event(Kind.BEGUN, null, null, null);
        }
        int at = stripped.indexOf(PREFIX);
        if (at < 0 || fromAnotherPlayer(stripped, at)) {
            return null;   // not ours, or somebody quoted it at us
        }
        String body = stripped.substring(at + PREFIX.length()).trim();

        Matcher appeared = APPEARED.matcher(body);
        if (appeared.find()) {
            Meal meal = Meal.of(appeared.group(1));
            return meal == null ? null : new Event(Kind.APPEARED, meal, null, null);
        }
        Matcher foundEgg = FOUND_EGG.matcher(body);
        if (foundEgg.find()) {
            Meal meal = Meal.of(foundEgg.group(1));
            // A named egg that is not one of the six meals is still an egg - "You found a Hitman
            // Egg!" is in the logs - but it is not one of the day's timed spawns, so it carries no
            // meal and the HUD does not tick a slot off for it.
            return new Event(Kind.FOUND_EGG, meal, foundEgg.group(1), null);
        }
        Matcher rabbit = FOUND_RABBIT.matcher(body);
        if (rabbit.find()) {
            return new Event(Kind.FOUND_RABBIT, null, rabbit.group(1), rabbit.group(2));
        }
        return null;
    }

    /** Feeds one line in and updates what is open. Called from the shared chat listener. */
    public Event onChat(String stripped) {
        Event event = parse(stripped);
        if (event == null) {
            return null;
        }
        switch (event.kind()) {
            case BEGUN -> {
                huntRunning = true;
                open.clear();
            }
            case APPEARED -> open.put(event.meal(), System.currentTimeMillis());
            case FOUND_EGG -> {
                if (event.meal() != null) {
                    open.remove(event.meal());
                }
            }
            case FOUND_RABBIT -> {
                // Nothing to track: the rabbit is what the egg held, not where it was.
            }
        }
        return event;
    }

    /** The meals that have appeared and not been collected, oldest first. */
    public Map<Meal, Long> open() {
        return Map.copyOf(open);
    }

    public boolean huntRunning() {
        return huntRunning;
    }

    /** Cleared on a world change: an egg that was up on the last server is not up here. */
    public void reset() {
        open.clear();
    }
}
