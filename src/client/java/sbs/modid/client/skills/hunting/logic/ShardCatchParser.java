/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.hunting.logic;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a chat line into "you gained N of this shard", and nothing else.
 *
 * <p>Extracted from {@code SafariTracker} so a second tracker does not carry a second copy of these
 * patterns. They are not obvious: the verb is a list because fishing says "caught" while hunting says
 * "found" and "obtained", three amount forms occur, and the Hunting Box line has to top up the catch
 * line it follows rather than book a shard of its own. Two copies of that would drift, and the second
 * copy would drift silently - nothing tells you a tracker quietly stopped counting.
 *
 * <p><b>No Minecraft in here</b>, which is the point: the rule is exercised by tests instead of by
 * standing in a Safari with a net. It keeps the small amount of state the top-up rule needs, so each
 * tracker owns its own parser instance and their dedupe windows cannot interfere.
 */
public final class ShardCatchParser {

    /**
     * "You caught a Lapis Zombie Shard!" - the catch itself. The verb is deliberately a list: the
     * fishing variant says "caught", the hunting ones "found" and "obtained", and a zone whose
     * wording cannot be checked from here is better served by a pattern that survives one. Amount
     * forms "2", "x2" and "2x" all parse.
     */
    private static final Pattern SHARD_GAINED = Pattern.compile(
            "You (?:caught|found|obtained|got|collected) (?:an? )?(?:x?(\\d+)x? )?(.+?) Shards?[!.]");

    /** "LOOT SHARE You received a Bal Shard for assisting ..." - a party member's kill still pays you. */
    private static final Pattern SHARD_SHARED = Pattern.compile(
            "You received (?:an? )?(?:x?(\\d+)x? )?(.+?) Shards? for assisting");

    /**
     * "You sent 3 Tide Shards to your Hunting Box." - the authority on how many shards a catch
     * produced, and therefore always tried last: it tops its own catch line up rather than opening a
     * booking of its own.
     */
    private static final Pattern SHARD_BOXED = Pattern.compile(
            "You sent (?:an? )?(?:x?(\\d+)x? )?(.+?) Shards? to your Hunting Box");

    /** Anything shard-flavoured that matched none of the above is worth a log line. */
    private static final Pattern SHARD_ISH = Pattern.compile("(?i)\\bshards?\\b");

    /** A player-written line ("Name: gg shard"); never a server announcement, never a catch. */
    private static final Pattern PLAYER_CHAT = Pattern.compile(
            "^(?:(?:Guild|Party|Co-op|Officer|G|P|O) > )?(?:\\[[^\\]]+\\] )*[A-Za-z0-9_]{2,16}[^:]{0,4}: ");

    /** A box line this long after its own catch line is a separate event, not a top-up. */
    private static final long DEDUPE_MS = 1_500L;

    /** One booking: how many of which shard to add now. Never zero - a no-op returns empty instead. */
    public record Catch(String shard, int amount, boolean fromBox) {
    }

    private String lastName = "";
    private int lastAmount;
    private long lastAt;
    private boolean lastBoxed;

    /**
     * Reads one colour-stripped line.
     *
     * <p>Returns empty for anything that is not a catch, <b>and</b> for a box line that only confirms
     * a catch already booked at the same amount - there is nothing to add in that case.
     *
     * @param line colour-stripped chat
     * @param now  the clock, passed in so the dedupe window is testable
     */
    public Optional<Catch> accept(String line, long now) {
        if (line == null || line.isEmpty() || PLAYER_CHAT.matcher(line).find()) {
            return Optional.empty();
        }
        Optional<Catch> gained = book(SHARD_GAINED, line, now, false);
        if (gained.isPresent()) {
            return gained;
        }
        Optional<Catch> shared = book(SHARD_SHARED, line, now, false);
        if (shared.isPresent()) {
            return shared;
        }
        return book(SHARD_BOXED, line, now, true);
    }

    /** Whether a line mentions shards but matched nothing - worth logging so a reword is visible. */
    public static boolean looksLikeShard(String line) {
        return line != null && SHARD_ISH.matcher(line).find()
                && !PLAYER_CHAT.matcher(line).find();
    }

    /**
     * Books one pattern.
     *
     * <p>A box line following its own catch line within {@value #DEDUPE_MS} ms is a top-up, not a
     * second shard: the catch line says "a Tide Shard" while the box line carries the real total once
     * Hunter Fortune multiplied it, so only the shortfall is added. Two catch lines in a row are
     * always two catches - the pair rule deliberately only collapses lines of different kinds.
     */
    private Optional<Catch> book(Pattern pattern, String line, long now, boolean boxed) {
        Matcher matcher = pattern.matcher(line);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String name = matcher.group(2) == null ? "" : matcher.group(2).trim();
        if (name.isEmpty()) {
            return Optional.empty();
        }
        int amount = parseCount(matcher.group(1));

        boolean topUp = boxed && !lastBoxed && name.equalsIgnoreCase(lastName)
                && now - lastAt < DEDUPE_MS;
        if (topUp) {
            lastAt = now;
            int extra = amount - lastAmount;
            lastAmount = Math.max(lastAmount, amount);
            lastBoxed = true;
            return extra > 0 ? Optional.of(new Catch(name, extra, true)) : Optional.empty();
        }

        lastName = name;
        lastAmount = amount;
        lastAt = now;
        lastBoxed = boxed;
        return Optional.of(new Catch(name, amount, boxed));
    }

    /** "", "2", "x2" and "2x" all mean what they look like; anything unreadable is one. */
    private static int parseCount(String group) {
        if (group == null || group.isEmpty()) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(group));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** Forgets the dedupe state, for a tracker starting a fresh session. */
    public void reset() {
        lastName = "";
        lastAmount = 0;
        lastAt = 0;
        lastBoxed = false;
    }
}
