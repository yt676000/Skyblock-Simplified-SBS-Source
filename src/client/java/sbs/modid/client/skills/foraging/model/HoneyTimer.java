/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.foraging.model;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One honey tree's running cooldown.
 *
 * <p>A plain mutable POJO rather than a record because Gson builds it field by field out of the
 * per-profile file, exactly like {@code Waypoint} and every other persisted settings object here.
 *
 * <p><b>The key is the identity and is assigned once</b> ({@code AGENTS.md}, Identity). It is the
 * island plus either the preset point's id or the block position, and it is never built from the
 * display label - renaming a preset point would otherwise destroy the player's running timer with
 * no error anywhere.
 *
 * <p><b>The island is in the key, and that is load-bearing rather than decorative.</b> SkyBlock
 * islands share one coordinate space and these two overlap: Moonglade Marsh's shipped trees span
 * x -733..-606 and Torrhus Canyon's span x -618..-513. A dynamic entry keyed on position alone
 * would collide across the two islands.
 *
 * <p><b>Everything is wall clock.</b> {@link #startedAt} is epoch millis and nothing counts ticks,
 * which is what makes a relog, a server hop or a crash restore the right remaining time instead of
 * restarting the count.
 */
public final class HoneyTimer {

    /** {@code <island>|preset:<point id>} or {@code <island>|dyn:<x>,<y>,<z>}. Assigned once. */
    public String key = "";

    /** The island this tree stands on, as {@code SkyBlockLocation} reported it. */
    public String island = "";

    public int x;
    public int y;
    public int z;

    /**
     * What to call it on screen, captured when the timer started.
     *
     * <p>Display only, and only the fallback: the live preset name wins whenever the point still
     * resolves, so correcting a label in the data file corrects it here too. It is stored at all
     * because a dynamic entry has no preset to ask.
     */
    public String label = "";

    /** When the smear happened, epoch millis. */
    public long startedAt;

    /** How long this entry counts down, in millis. */
    public long durationMs;

    /**
     * Whether {@link #durationMs} should follow the configured duration live.
     *
     * <p>True for an entry started from the setting, false for one whose duration came from a
     * reading of the world. The distinction is the difference between an assumption and evidence:
     * a player who learns the real cooldown mid-session wants every assumed timer to correct
     * itself, and wants a measured one left alone.
     */
    public boolean followsConfig = true;

    /** Whether this was registered at the hit position rather than snapped to a preset tree. */
    public boolean dynamic;

    /**
     * Whether a chat line confirmed the smear.
     *
     * <p>False means the timer started on the fallback - the click was armed and no line we
     * recognised arrived. Shown with a {@code ?} so a guessed timer is never presented as a known
     * one, which is the whole reason the flag is persisted rather than kept in memory.
     */
    public boolean confirmed;

    /**
     * The instance it was started on ({@code Server: mini24CD}), or empty when it was not readable.
     *
     * <p>Recorded because whether this cooldown is per-server or global is an open question. If it
     * is per-server, a timer carried across a hop is wrong, and the honest thing is to mark it as
     * foreign rather than either hide it or present it as authoritative.
     */
    public String serverName = "";

    /** Whether the pre-warning has already gone out for this run. Reset when the timer restarts. */
    public boolean notifiedWarn;

    /** Whether the expiry notification has already gone out for this run. */
    public boolean notifiedDone;

    /** Gson needs a no-arg constructor. */
    public HoneyTimer() {
    }

    /** The key for a tree snapped to a shipped preset point. */
    public static String presetKey(String island, String pointId) {
        return island + "|preset:" + pointId;
    }

    /** The key for a tree we do not have coordinates for. */
    public static String dynamicKey(String island, int x, int y, int z) {
        return island + "|dyn:" + x + "," + y + "," + z;
    }

    /** When this timer runs out, epoch millis. */
    public long endsAt() {
        return startedAt + durationMs;
    }

    /** Millis left, never below zero. */
    public long remainingMs(long now) {
        return Math.max(0L, endsAt() - now);
    }

    /** Whether the tree is smearable again. */
    public boolean ready(long now) {
        return now >= endsAt();
    }

    /** How long it has been ready, for the pruning window; zero while it is still running. */
    public long expiredForMs(long now) {
        return Math.max(0L, now - endsAt());
    }

    /** Whether the record has enough in it to be counted down at all. */
    public boolean valid() {
        return key != null && !key.isBlank() && startedAt > 0L && durationMs > 0L;
    }

    /**
     * {@code MM:SS}, or {@code Ready}.
     *
     * <p>Minutes are not clamped to two digits: a duration override of 60 minutes is allowed, and
     * truncating it would be a wrong number rather than a tidy one.
     */
    public static String clock(long remainingMs) {
        if (remainingMs <= 0L) {
            return "Ready";
        }
        long totalSeconds = (remainingMs + 999L) / 1000L;   // round up, so 0:00 means 0:00
        return String.format(Locale.ROOT, "%d:%02d", totalSeconds / 60L, totalSeconds % 60L);
    }

    /**
     * {@code 14:59}.
     *
     * <p><b>Bounded by "not a digit" rather than by a word boundary</b>, and that is not a
     * refinement - it is the difference between working and not. Hypixel colours words mid-phrase,
     * so a nametag reading {@code Honey Tree§e14:59} strips to {@code Honey Tree14:59} with the
     * digits glued to a letter, where {@code \b} does not match at all. The digit guard still
     * refuses {@code 118:274}, which is what stops a coordinate pair being read as a time.
     */
    private static final Pattern CLOCK = Pattern.compile("(?<!\\d)(\\d{1,3}):([0-5]\\d)(?!\\d)");

    /**
     * {@code 14m 59s}, {@code 14 minutes}, {@code 59s} - the other way Hypixel writes a duration.
     *
     * <p>The units are spelled out rather than left as a bare {@code m}/{@code s} so that
     * {@code 5 stacks} cannot be read as five seconds: each unit has to end at a word boundary, so
     * a letter continuing the word rejects the match instead of truncating it.
     */
    private static final Pattern WORDS = Pattern.compile(
            "(?<!\\d)(?:(\\d{1,3})\\s*(?:minutes|minute|mins|min|m)\\b)?"
                    + "\\s*(?:(\\d{1,2})\\s*(?:seconds|second|secs|sec|s)\\b)?");

    /**
     * A duration written in text, in millis, or {@code 0} when there is none.
     *
     * <p>Lives beside {@link #clock} because it is the same question in the other direction, and
     * here rather than in the reader that needs it because this class has no Minecraft imports -
     * which is what lets the shapes be exercised in {@code src/test} instead of only on Galatea.
     *
     * <p>Both shapes are tried because nobody has seen the one Hypixel actually uses. Neither is a
     * claim: a string matching neither returns zero, and the caller treats that as "the world said
     * nothing" rather than as an error.
     */
    public static long parseDuration(String text) {
        if (text == null || text.isEmpty()) {
            return 0L;
        }
        Matcher clock = CLOCK.matcher(text);
        if (clock.find()) {
            long minutes = Long.parseLong(clock.group(1));
            long seconds = Long.parseLong(clock.group(2));
            return (minutes * 60L + seconds) * 1000L;
        }
        Matcher words = WORDS.matcher(text);
        while (words.find()) {
            String minutes = words.group(1);
            String seconds = words.group(2);
            if (minutes == null && seconds == null) {
                continue;
            }
            long total = 0L;
            if (minutes != null) {
                total += Long.parseLong(minutes) * 60L;
            }
            if (seconds != null) {
                total += Long.parseLong(seconds);
            }
            if (total > 0L) {
                return total * 1000L;
            }
        }
        return 0L;
    }
}
