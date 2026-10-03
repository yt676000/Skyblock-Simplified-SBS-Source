/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.model;

import sbs.modid.client.core.build.model.StateStrings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What an edit puts down: one block ({@code stone}), or a weighted mix
 * ({@code 50%stone,30%andesite,20%cobblestone}; entries without a percentage share what is left).
 *
 * <p>Which block of a mix lands where is a pure function of the position ({@link #pick}), not a
 * random draw at apply time - so the preview hologram shows exactly the blocks that Enter places, and
 * an undo followed by the same edit reproduces them.
 *
 * <p>Parsing is pure and checks only the syntax; whether each id is a real block is the game's
 * question and is asked by the command before anything is planned.
 */
public final class BlockPattern {

    /**
     * One entry: a block-state string and its weight (any positive number). {@code heldName} is the
     * block's display name when it came from the player's hand ({@code hand} / {@code offhand}), else
     * {@code null}.
     */
    public record Entry(String state, double weight, String heldName) {

        public Entry(String state, double weight) {
            this(state, weight, null);
        }
    }

    /** The tokens that mean "the block I am holding". */
    public static final String HAND = "hand";
    public static final String OFFHAND = "offhand";

    /** Turns {@code hand} / {@code offhand} into the held block, or refuses with a sentence. */
    public interface HeldBlocks {

        /** The held block, for the main hand ({@code offhand == false}) or the off hand. */
        Held held(boolean offhand) throws PatternException;
    }

    /** A held block: its state string and its display name ("Oak Planks"). */
    public record Held(String state, String displayName) {
    }

    /** Refuses the hand tokens - for callers with no hands to look at. */
    public static final HeldBlocks NO_HANDS = offhand -> {
        throw new PatternException("\"" + (offhand ? OFFHAND : HAND) + "\" only works in game, holding a block");
    };

    private final List<Entry> entries;
    private final double[] cumulative;
    private final double total;

    private BlockPattern(List<Entry> entries) {
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        this.cumulative = new double[entries.size()];
        double sum = 0;
        for (int i = 0; i < entries.size(); i++) {
            sum += entries.get(i).weight();
            cumulative[i] = sum;
        }
        this.total = sum;
    }

    /** Thrown with a sentence for the player. */
    public static final class PatternException extends Exception {
        public PatternException(String message) {
            super(message);
        }
    }

    /**
     * Parses {@code stone}, {@code minecraft:oak_stairs[facing=east]}, {@code 50%stone,50%andesite}
     * or {@code stone,dirt} (equal shares). Ids get the {@code minecraft:} namespace when they have
     * none; properties are kept as written.
     */
    public static BlockPattern parse(String text) throws PatternException {
        return parse(text, NO_HANDS);
    }

    /**
     * As {@link #parse(String)}, with {@code hand} and {@code offhand} accepted anywhere an id is -
     * alone or in a mix ({@code 50%hand,50%stone}) - and resolved through {@code held}.
     */
    public static BlockPattern parse(String text, HeldBlocks held) throws PatternException {
        if (text == null || text.isBlank()) {
            throw new PatternException("no block given");
        }
        List<String> parts = splitTopLevel(text.trim());
        List<String> states = new ArrayList<>();
        List<String> heldNames = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        double explicit = 0;
        int unweighted = 0;
        for (String raw : parts) {
            String part = raw.trim();
            if (part.isEmpty()) {
                throw new PatternException("an empty entry in \"" + text + "\"");
            }
            Double weight = null;
            int percent = part.indexOf('%');
            if (percent >= 0) {
                String number = part.substring(0, percent).trim();
                try {
                    weight = Double.parseDouble(number);
                } catch (NumberFormatException bad) {
                    throw new PatternException("\"" + number + "%\" is not a percentage");
                }
                if (weight <= 0) {
                    throw new PatternException("a share must be more than 0%");
                }
                part = part.substring(percent + 1).trim();
                explicit += weight;
            } else {
                unweighted++;
            }
            if (part.isEmpty()) {
                throw new PatternException("a share without a block in \"" + text + "\"");
            }
            if (part.equalsIgnoreCase(HAND) || part.equalsIgnoreCase(OFFHAND)) {
                Held block = held.held(part.equalsIgnoreCase(OFFHAND));
                states.add(block.state());
                heldNames.add(block.displayName());
            } else {
                states.add(normalise(part));
                heldNames.add(null);
            }
            weights.add(weight);
        }
        if (explicit > 100.0001) {
            throw new PatternException("the shares add up to " + trim(explicit) + "%, more than 100%");
        }
        double share;
        if (unweighted == 0) {
            share = 0;
        } else if (explicit == 0) {
            share = 1;
        } else {
            share = Math.max(0, 100 - explicit) / unweighted;
            if (share == 0) {
                throw new PatternException("the shares already make 100%, nothing is left for the rest");
            }
        }
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < states.size(); i++) {
            Double weight = weights.get(i);
            entries.add(new Entry(states.get(i), weight == null ? share : weight, heldNames.get(i)));
        }
        return new BlockPattern(entries);
    }

    /** A one-block pattern. */
    public static BlockPattern of(String state) {
        return new BlockPattern(List.of(new Entry(state, 1)));
    }

    public List<Entry> entries() {
        return entries;
    }

    public boolean single() {
        return entries.size() == 1;
    }

    /**
     * The state for position {@code (x, y, z)}: always the same for the same position, spread across
     * the entries by their weights.
     */
    public String pick(int x, int y, int z) {
        if (entries.size() == 1) {
            return entries.get(0).state();
        }
        double roll = unitHash(x, y, z) * total;
        for (int i = 0; i < cumulative.length; i++) {
            if (roll < cumulative[i]) {
                return entries.get(i).state();
            }
        }
        return entries.get(entries.size() - 1).state();
    }

    /** {@code stone} or {@code 50% stone, 50% andesite}, for history names and chat. */
    public String describe() {
        if (entries.size() == 1) {
            return name(entries.get(0));
        }
        StringBuilder out = new StringBuilder();
        for (Entry entry : entries) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(trim(entry.weight() * 100 / total)).append("% ").append(name(entry));
        }
        return out.toString();
    }

    /** "oak planks", or "Oak Planks (from your hand)" for a held block. */
    private static String name(Entry entry) {
        return entry.heldName() == null ? StateStrings.displayName(entry.state())
                : entry.heldName() + " (from your hand)";
    }

    /** A well-mixed value in [0, 1) from a position - a fixed hash, not a random generator. */
    static double unitHash(int x, int y, int z) {
        long h = x * 0x9E3779B97F4A7C15L ^ y * 0xC2B2AE3D27D4EB4FL ^ z * 0x165667B19E3779F9L;
        h ^= (h >>> 33);
        h *= 0xFF51AFD7ED558CCDL;
        h ^= (h >>> 33);
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= (h >>> 33);
        return (h >>> 11) * 0x1.0p-53;
    }

    /** Splits on commas outside brackets, so {@code oak_stairs[facing=east,half=top]} stays whole. */
    private static List<String> splitTopLevel(String text) throws PatternException {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth < 0) {
                    throw new PatternException("a ']' without its '[' in \"" + text + "\"");
                }
            } else if (c == ',' && depth == 0) {
                out.add(text.substring(start, i));
                start = i + 1;
            }
        }
        if (depth != 0) {
            throw new PatternException("a '[' without its ']' in \"" + text + "\"");
        }
        out.add(text.substring(start));
        return out;
    }

    private static String normalise(String state) {
        String id = StateStrings.blockId(state);
        return StateStrings.withNamespace(id) + state.substring(id.length());
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
