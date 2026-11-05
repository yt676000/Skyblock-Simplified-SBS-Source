/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.fishing.logic;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Attribute-shard chat lines, and how many shards each one adds to the session.
 *
 * <p>Shards go straight to the Hunting Box, so chat is the only place they can be counted. The catch
 * line books immediately - it is the only line guaranteed to arrive the moment a shard is fished; the
 * Hunting Box transfer line can lag by seconds or never come. When the box line DOES follow within
 * {@link #DEDUPE_MS} for the same shard it is the authority on the amount: with Hunter Fortune the
 * catch line stays "You caught a Tide Shard!" while "You sent 2 Tide Shards to your Hunting Box."
 * carries the real total, so the pair books exactly the shortfall on top. A box line on its own - a
 * loot share's shard arrives that way - is a booking of its own.
 *
 * <p>Moved out of {@link FishingTracker} unchanged in behaviour so the once-only rule is tested.
 */
public final class ShardLines {

    /** Two shard lines for the same shard within this window are one shard re-announced. */
    static final long DEDUPE_MS = 10_000L;

    /**
     * "GOOD CATCH! You caught a Shinyfish Shard!" / "You caught x2 Bal Shards!" - deliberately
     * unanchored: the fishing variant carries an icon + "GOOD CATCH!" prefix (seen live), the
     * hunting variant does not. Amount forms "2", "x2" and "2x" all parse.
     */
    private static final Pattern CAUGHT = Pattern.compile(
            "You caught(?: an?)?(?: x?(\\d+)x?)? (.+?) Shards?!");
    /** "You sent a Tide Shard to your Hunting Box." / "You sent 3 Tide Shards to your ..." - the
     *  authority on how many shards a catch produced. The trailing punctuation is left open on
     *  purpose, a reworded ending must not lose the line. */
    private static final Pattern BOXED = Pattern.compile(
            "You sent (?:an? )?(?:x?(\\d+)x? )?(.+?) Shards? to your Hunting Box");
    /** "LOOT SHARE You received a Bal Shard for assisting ..." - shards you get are income too. */
    private static final Pattern SHARED = Pattern.compile(
            "LOOT SHARE You received (?:an? )?(?:x?(\\d+)x? )?(.+?) Shards? for assisting");
    /**
     * "CHARM! You charmed the Thunder and received 2 Thunder Shards!" - a sea creature killed with a
     * charm effect pays its attribute shard straight to the Hunting Box, announced only by this line
     * (2026-10-08 Crimson Isle session: Thunder x2, Magma Slug x1, Lord Jawbus x1).
     */
    private static final Pattern CHARMED = Pattern.compile(
            "You charmed (?:the |an? )?.+? and received (?:an? )?(?:x?(\\d+)x? )?(.+?) Shards?!");
    /** Every shard line, tried in order; all bind (amount?, name) as groups 1 and 2. */
    private static final Pattern[] LINES = {CAUGHT, BOXED, SHARED, CHARMED};

    /** What one line means: the shard's display name and how many to book now (0: recognised only). */
    public record Booking(String shardName, int amount) {
    }

    private String lastName = "";
    private long lastAt;
    /** Which of {@link #LINES} the last booking line matched, or -1. */
    private int lastPattern = -1;
    /** How many of that shard the last line booked - the box line may know of MORE. */
    private int lastAmount;

    /**
     * Reads one chat line.
     *
     * @param allowed whether a shard may be booked at all now (fishing recently, or a loot share);
     *                a line that is not allowed is still recognised so nothing else parses it
     * @return null when {@code message} is no shard line
     */
    public Booking onLine(String message, long now, boolean allowed) {
        for (int i = 0; i < LINES.length; i++) {
            Matcher matcher = LINES[i].matcher(message);
            if (!matcher.find()) {
                continue;
            }
            String name = matcher.group(2).trim();
            int amount = parseCount(matcher.group(1));
            // Only the box line RE-announces a shard, always after the catch / loot-share / charm
            // line of the same catch. Catch lines always open a new booking: a second "You caught a
            // Tide Shard!" right after a box line is a second catch, not an echo.
            boolean topUp = LINES[i] == BOXED
                    && lastPattern >= 0 && LINES[lastPattern] != BOXED
                    && name.equalsIgnoreCase(lastName)
                    && now - lastAt < DEDUPE_MS;
            if (topUp) {
                lastAt = now;
                if (allowed && amount > lastAmount) {
                    int extra = amount - lastAmount;
                    lastAmount = amount;
                    return new Booking(name, extra);
                }
                return new Booking(name, 0);
            }
            lastName = name;
            lastAt = now;
            lastPattern = i;
            lastAmount = allowed ? amount : 0;
            return new Booking(name, allowed ? amount : 0);
        }
        return null;
    }

    public void reset() {
        lastName = "";
        lastAt = 0;
        lastPattern = -1;
        lastAmount = 0;
    }

    /** "1,542" / "x2" -> the number; a missing group counts as 1 (the lines omit "x1"). */
    private static int parseCount(String group) {
        if (group == null) {
            return 1;
        }
        int value = 0;
        boolean any = false;
        for (int i = 0; i < group.length(); i++) {
            char c = group.charAt(i);
            if (c >= '0' && c <= '9') {
                value = value * 10 + (c - '0');
                any = true;
            }
        }
        return any ? value : 1;
    }
}
