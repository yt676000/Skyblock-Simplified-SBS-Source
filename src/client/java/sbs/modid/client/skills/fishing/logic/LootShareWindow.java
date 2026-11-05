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
 * When a loot share happened, so the loot it pays out can be booked to the fishing session.
 *
 * <p><b>The line.</b> "LOOT SHARE You received loot for assisting &lt;name&gt;!" names no item: the
 * loot itself arrives through the ordinary channels right after - the "[Sacks] +N items. (Last Ns.)"
 * summary with its hover breakdown, the inventory, or a Hunting Box line for a shard. Wording
 * {@code CONFIRMED} from the maintainer's chat logs (thousands of occurrences), 2026-10-12. Before
 * this class the line was recognised nowhere, so a loot-share drop counted only when it happened to
 * pass the own-catch rules, and shards from a loot share never did while the player was not
 * catching anything themselves.
 *
 * <p><b>The window.</b> Inventory gains and shard lines count as loot-share drops for
 * {@link #WINDOW_MS} after the line. A sack summary is aggregated, so it counts when the period it
 * reports ("Last Ns") reaches back to a loot share, plus {@link #SACK_SLACK_MS}.
 *
 * <p>Kept free of Minecraft so the window is tested, not fished for.
 */
public final class LootShareWindow {

    /** How long after a loot share line its inventory gains and shard lines are still its loot. */
    static final long WINDOW_MS = 3_000L;

    /** Slack around a sack summary's own period, for the line arriving a beat after it closes. */
    static final long SACK_SLACK_MS = 1_500L;

    /** "LOOT SHARE You received loot for assisting Name!" - matched on colour-stripped text. */
    private static final Pattern LOOT_LINE = Pattern.compile(
            "^LOOT SHARE You received loot for assisting .+");

    /** The "(Last 5s.)" period of a "[Sacks]" line. */
    private static final Pattern SACK_PERIOD = Pattern.compile("\\(Last (\\d+)s\\.?\\)");

    /** The latest loot share, or -1. */
    private long lastAt = -1;

    /** Whether {@code plain} is the generic loot-share line (the shard variant is a shard line). */
    public static boolean isLootShareLine(String plain) {
        return plain != null && plain.startsWith("LOOT SHARE") && LOOT_LINE.matcher(plain).find();
    }

    /** The seconds a "[Sacks]" line covers, or -1 when it states none. */
    public static int sackPeriodSeconds(String plain) {
        if (plain == null || !plain.startsWith("[Sacks]")) {
            return -1;
        }
        Matcher matcher = SACK_PERIOD.matcher(plain);
        if (!matcher.find()) {
            return -1;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** A loot share line arrived at {@code now}. */
    public void open(long now) {
        lastAt = now;
    }

    /** Whether an inventory gain or shard line at {@code now} is loot-share loot. */
    public boolean covers(long now) {
        return lastAt >= 0 && now >= lastAt && now - lastAt <= WINDOW_MS;
    }

    /**
     * Whether a sack summary arriving at {@code now} and covering the last {@code periodSeconds}
     * holds loot-share loot. A summary without a period is judged like an inventory gain.
     */
    public boolean coversSack(long now, int periodSeconds) {
        if (lastAt < 0 || now < lastAt) {
            return false;
        }
        long reach = periodSeconds < 0 ? WINDOW_MS : periodSeconds * 1000L + SACK_SLACK_MS;
        return now - lastAt <= reach;
    }

    public void reset() {
        lastAt = -1;
    }
}
