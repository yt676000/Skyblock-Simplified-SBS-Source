/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.puzzle.logic;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pure half of the Higher or Lower (blaze) puzzle: health off a nametag, which blaze is next, and
 * which way the room runs. No Minecraft types, so it is tested without a game.
 *
 * <p>The mechanic (wiki): kill the blazes in health order; killing one out of order fails the room.
 * The order depends on the room variant - <b>chest at the bottom: lowest health first; chest at the
 * top: highest health first</b>. {@link #directionFromChest} is that rule and nothing more.
 *
 * <p>Every "not sure" answer here is a refusal ({@code -1} / {@code null}), never a best guess: a
 * wrong blaze fails the puzzle, which costs more than no hint.
 */
public final class BlazeOrder {

    /** Which end of the health order is killed first. */
    public enum Direction {
        LOWEST_FIRST,
        HIGHEST_FIRST
    }

    /** A health token in a blaze nametag, e.g. "46M", "1,200,000", "12.5k". */
    private static final Pattern HEALTH = Pattern.compile("([0-9][0-9.,]*)\\s*([kKmMbB]?)");

    private BlazeOrder() {
    }

    /**
     * The largest health-looking number in a colour-stripped nametag (k/m/b suffixes), or 0 when none.
     *
     * <p><b>The nametag shape is unverified</b> - no blaze has been captured yet. Taking the largest
     * number is the pre-existing behaviour, kept unchanged; it would be wrong for a
     * {@code current/max} tag, where it picks the max. See the spec's "Not done yet".
     */
    public static long parseHealth(String plain) {
        if (plain == null || plain.isEmpty()) {
            return 0;
        }
        long best = 0;
        Matcher matcher = HEALTH.matcher(plain);
        while (matcher.find()) {
            try {
                double value = Double.parseDouble(matcher.group(1).replace(",", ""));
                switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
                    case "k" -> value *= 1_000;
                    case "m" -> value *= 1_000_000;
                    case "b" -> value *= 1_000_000_000;
                    default -> { }
                }
                best = Math.max(best, (long) value);
            } catch (NumberFormatException ignored) {
                // "1.2.3" and the like - not a number, skip it
            }
        }
        return best;
    }

    /**
     * The index of the blaze to kill next, or {@code -1} when there is no safe answer: fewer than
     * two blazes (a lone blaze is not the puzzle), an unread health ({@code <= 0}), or a tie at the
     * end being killed - with two blazes on the same health there is no single "next", and picking
     * one is a guess.
     */
    public static int nextIndex(long[] healths, Direction direction) {
        if (healths == null || healths.length < 2 || direction == null) {
            return -1;
        }
        int best = -1;
        boolean tied = false;
        for (int i = 0; i < healths.length; i++) {
            long health = healths[i];
            if (health <= 0) {
                return -1;
            }
            if (best < 0) {
                best = i;
                continue;
            }
            long current = healths[best];
            boolean better = direction == Direction.HIGHEST_FIRST ? health > current : health < current;
            if (better) {
                best = i;
                tied = false;
            } else if (health == current) {
                tied = true;
            }
        }
        return tied ? -1 : best;
    }

    /**
     * The blaze after {@code next}: the next among the others, or the last one standing when only
     * one other is left. {@code -1} when {@code next} is not a valid index or the others tie - the
     * same rule as {@link #nextIndex}, one step ahead (asked 2026-10-17: show the one after too).
     */
    public static int afterIndex(long[] healths, Direction direction, int next) {
        if (healths == null || next < 0 || next >= healths.length || healths.length < 2) {
            return -1;
        }
        long[] rest = new long[healths.length - 1];
        int[] map = new int[rest.length];
        for (int i = 0, j = 0; i < healths.length; i++) {
            if (i != next) {
                rest[j] = healths[i];
                map[j++] = i;
            }
        }
        if (rest.length == 1) {
            return rest[0] > 0 ? map[0] : -1;
        }
        int pick = nextIndex(rest, direction);
        return pick < 0 ? -1 : map[pick];
    }

    /**
     * The room variant from where its chest sits relative to the blazes: below every blaze is
     * {@link Direction#LOWEST_FIRST}, above every blaze is {@link Direction#HIGHEST_FIRST}, and
     * anything in between is {@code null} - the rule says top or bottom, and a chest level with the
     * blazes is neither.
     */
    public static Direction directionFromChest(int chestY, double minBlazeY, double maxBlazeY) {
        if (minBlazeY > maxBlazeY) {
            return null;
        }
        if (chestY < minBlazeY) {
            return Direction.LOWEST_FIRST;
        }
        if (chestY > maxBlazeY) {
            return Direction.HIGHEST_FIRST;
        }
        return null;
    }
}
