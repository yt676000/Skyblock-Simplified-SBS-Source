/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.forge.logic;

import sbs.modid.client.economy.forge.model.ForgeSlotTimer;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads one forge slot's state out of its colour-stripped lore, and does the finish-time maths.
 *
 * <p><b>No Minecraft imports</b>, so the shapes are exercised in {@code src/test} rather than only
 * in the Dwarven Mines.
 *
 * <p><b>The wording is UNVERIFIED.</b> Nobody has opened the forge with {@code /sbs probe} yet
 * ({@code docs/skyblock-ui/menus.md}, The Forge). The expected shapes - {@code Time Remaining:
 * 2h 13m} while running, {@code Click to claim!} / {@code Ready} when done - are the ones the
 * request named, and the matcher is deliberately tolerant around them: any line carrying a time
 * word plus a duration counts, and a line that is only a duration counts. A slot matching neither
 * is reported as {@link State#UNKNOWN} and the reader logs its lore, which is how one visit to the
 * forge replaces the guess.
 */
public final class ForgeSlotParser {

    private ForgeSlotParser() {
    }

    /** What a slot's lore says. */
    public enum State { RUNNING, READY, UNKNOWN }

    /** @param remainingMs millis left; {@code 0} for READY and UNKNOWN */
    public record Reading(State state, long remainingMs) {
        static final Reading UNKNOWN = new Reading(State.UNKNOWN, 0L);
    }

    /** "Click to claim", "Ready", "Completed", "Finished" - as a word, so "Already" is not one. */
    private static final Pattern READY_LINE = Pattern.compile(
            "\\b(?:click to claim|ready to claim|ready|completed|finished|claim)\\b",
            Pattern.CASE_INSENSITIVE);

    /** A line naming the countdown: "Time Remaining:", "Time Left:", "Ends in", "Remaining". */
    private static final Pattern TIME_WORD = Pattern.compile(
            "time\\s+(?:remaining|left)|remaining|ends?\\s+in|ready\\s+in|finish(?:es)?\\s+in",
            Pattern.CASE_INSENSITIVE);

    /**
     * One unit of a duration: {@code 2h}, {@code 13m}, {@code 1d}, {@code 5s}, also spelled out
     * ({@code 2 hours}). The unit must end the word, so {@code 5 stacks} is not five seconds.
     */
    private static final Pattern UNIT = Pattern.compile(
            "(?<![\\d.])(\\d{1,4})\\s*(days|day|d|hours|hour|hrs|hr|h|minutes|minute|mins|min|m"
                    + "|seconds|second|secs|sec|s)\\b",
            Pattern.CASE_INSENSITIVE);

    /** {@code 2:13:05} or {@code 13:05}, bounded by not-a-digit. */
    private static final Pattern CLOCK = Pattern.compile(
            "(?<!\\d)(?:(\\d{1,3}):)?(\\d{1,2}):([0-5]\\d)(?!\\d)");

    /** A line that is nothing but a duration ("2h 13m 5s"). */
    private static final Pattern ONLY_DURATION = Pattern.compile(
            "^\\s*(?:\\d{1,4}\\s*[a-zA-Z]{1,7}\\s*){1,4}$");

    /**
     * The slot's state. A running countdown wins over a ready word, because "Time Remaining: 2h
     * (click to claim when finished)" is still running, and a READY slot has no duration left.
     */
    public static Reading read(List<String> lore) {
        if (lore == null || lore.isEmpty()) {
            return Reading.UNKNOWN;
        }
        boolean readyWord = false;
        for (String raw : lore) {
            if (raw == null) {
                continue;
            }
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            boolean timeLine = TIME_WORD.matcher(line).find()
                    || ONLY_DURATION.matcher(line).matches();
            if (timeLine) {
                long ms = parseDuration(line);
                if (ms > 0L) {
                    return new Reading(State.RUNNING, ms);
                }
            }
            if (READY_LINE.matcher(line).find() && !line.toLowerCase(Locale.ROOT).contains("ready in")) {
                readyWord = true;
            }
        }
        return readyWord ? new Reading(State.READY, 0L) : Reading.UNKNOWN;
    }

    /** A duration written in text, in millis; {@code 0} when there is none. */
    public static long parseDuration(String text) {
        if (text == null || text.isEmpty()) {
            return 0L;
        }
        Matcher clock = CLOCK.matcher(text);
        if (clock.find()) {
            long h = clock.group(1) == null ? 0L : Long.parseLong(clock.group(1));
            long m = Long.parseLong(clock.group(2));
            long s = Long.parseLong(clock.group(3));
            if (clock.group(1) == null) {
                // "13:05" is minutes:seconds - the shorter form only appears under an hour.
                return (m * 60L + s) * 1000L;
            }
            return ((h * 60L + m) * 60L + s) * 1000L;
        }
        long total = 0L;
        Matcher unit = UNIT.matcher(text);
        while (unit.find()) {
            long n = Long.parseLong(unit.group(1));
            char u = Character.toLowerCase(unit.group(2).charAt(0));
            total += switch (u) {
                case 'd' -> n * 86_400L;
                case 'h' -> n * 3_600L;
                case 'm' -> n * 60L;
                default -> n;
            };
        }
        return total * 1000L;
    }

    /**
     * When a slot read now finishes. A READY slot finished at the latest now; it is stamped with
     * {@code now} so it sorts first and counts as done immediately.
     */
    public static long finishAt(Reading reading, long now) {
        return reading.state() == State.RUNNING ? now + reading.remainingMs() : now;
    }

    /**
     * Merges a fresh reading onto what was stored for the slot, keeping the alert flag when it is
     * still the same item on the same run.
     *
     * <p>"Same run" is the same item with a finish time within a minute of the stored one - the
     * menu rounds its countdown, so two reads of one run never agree to the millisecond. A slot
     * read as already READY in the menu is marked notified: the player is looking at it, so an
     * alert about it would only be noise.
     */
    public static ForgeSlotTimer merge(ForgeSlotTimer previous, int slot, String item, int amount,
                                       Reading reading, long now) {
        ForgeSlotTimer next = new ForgeSlotTimer(slot, item, amount, finishAt(reading, now), now);
        boolean sameRun = previous != null && previous.item != null && previous.item.equals(next.item)
                && Math.abs(previous.finishAt - next.finishAt) <= 60_000L;
        if (sameRun) {
            // Keep the earlier estimate for a ready slot: it says when the item really finished.
            if (reading.state() == State.READY && previous.finishAt < next.finishAt) {
                next.finishAt = previous.finishAt;
            }
            next.notified = previous.notified;
        }
        if (reading.state() == State.READY) {
            next.notified = true;
        }
        return next;
    }

    /** "12m", "3h", "2d" - how old the data is, for "last seen". */
    public static String age(long readAt, long now) {
        long s = Math.max(0L, now - readAt) / 1000L;
        if (s < 3_600L) {
            return Math.max(1L, s / 60L) + "m";
        }
        if (s < 86_400L) {
            return s / 3_600L + "h";
        }
        return s / 86_400L + "d";
    }
}
