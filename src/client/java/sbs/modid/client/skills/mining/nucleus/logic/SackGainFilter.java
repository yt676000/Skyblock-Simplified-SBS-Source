/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.nucleus.logic;

import sbs.modid.client.skills.garden.pests.PestChat;
import sbs.modid.client.skills.mining.nucleus.model.NucleusRunData;
import sbs.modid.client.skills.mining.nucleus.model.NucleusSignals;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Decides which gains of one {@code [Sacks]} message are run loot. Pure.
 *
 * <p>A sack gain is an item arriving in a sack, which is not the same as a drop. The message is
 * skipped whole when it cannot be told apart from moving items the player already had:
 * <ul>
 *   <li>it also removes something - a craft or a withdrawal ({@code [Sacks] +35 items, -19,840 items.}
 *       after a Supercraft is in the logs);</li>
 *   <li>a container screen was open at any point of the message's own {@code (Last Ns.)} window -
 *       sack menu deposits, crafting, storage, trades and the Auction House all happen in one;</li>
 *   <li>a stash pickup line fell inside that window.</li>
 * </ul>
 * Whatever survives is first matched against the arrivals ledger - chest and bundle items already
 * counted from their reward block - so an explicit drop is never counted a second time when it
 * reaches the sack. The ledger is consumed even for a skipped message: those items did arrive.
 */
public final class SackGainFilter {

    private static final Pattern REMOVAL = Pattern.compile("(?:^|[\\s,])-[\\d,]+ items?\\b");

    private SackGainFilter() {
    }

    /**
     * @param booked what counts as run loot, by display name
     * @param skipped why the whole message was skipped, or {@code null}
     */
    public record Decision(Map<String, Long> booked, String skipped) {
    }

    /**
     * @param plain               the message, colour-stripped
     * @param gains               the hover's {@code +} lines by item name ({@code PestChat.sackGains})
     * @param now                 when the message arrived
     * @param lastContainerOpenAt the last moment a container screen was open, or {@code -1}
     * @param lastStashPickupAt   the last stash pickup line, or {@code -1}
     * @param arrivals            the ledger; matching entries are consumed
     */
    public static Decision evaluate(String plain, Map<String, Long> gains, long now,
                                    long lastContainerOpenAt, long lastStashPickupAt,
                                    List<NucleusRunData.Arrival> arrivals) {
        int periodSeconds = PestChat.sackPeriod(plain);
        long windowStart = now - Math.max(0, periodSeconds) * 1000L - NucleusSignals.SACK_WINDOW_SLACK_MS;
        String skipped = null;
        if (REMOVAL.matcher(plain).find()) {
            skipped = "removals in the same message";
        } else if (lastContainerOpenAt >= windowStart) {
            skipped = "a container was open in the window";
        } else if (lastStashPickupAt >= windowStart) {
            skipped = "stash pickup in the window";
        }
        Map<String, Long> booked = new LinkedHashMap<>();
        for (Map.Entry<String, Long> gain : gains.entrySet()) {
            String name = NucleusChatParser.withoutGlyph(gain.getKey().trim());
            long left = consume(arrivals, key(name), gain.getValue(), now);
            if (skipped == null && left > 0) {
                booked.merge(name, left, Long::sum);
            }
        }
        return new Decision(booked, skipped);
    }

    /** The ledger key for a display name. */
    public static String key(String name) {
        return NucleusChatParser.withoutGlyph(name.trim()).toLowerCase(Locale.ROOT);
    }

    /** Adds an expected arrival, merging into an existing entry for the same item. */
    public static void expect(List<NucleusRunData.Arrival> arrivals, String name, long qty, long expiresAt) {
        String key = key(name);
        for (NucleusRunData.Arrival arrival : arrivals) {
            if (arrival.key.equals(key)) {
                arrival.qty += qty;
                arrival.expiresAt = Math.max(arrival.expiresAt, expiresAt);
                return;
            }
        }
        arrivals.add(new NucleusRunData.Arrival(key, qty, expiresAt));
    }

    /** Drops expired entries. */
    public static void prune(List<NucleusRunData.Arrival> arrivals, long now) {
        arrivals.removeIf(arrival -> arrival.expiresAt <= now || arrival.qty <= 0);
    }

    /** Takes up to {@code qty} of {@code key} off the ledger and returns what was not expected. */
    static long consume(List<NucleusRunData.Arrival> arrivals, String key, long qty, long now) {
        long left = qty;
        Iterator<NucleusRunData.Arrival> it = arrivals.iterator();
        while (it.hasNext() && left > 0) {
            NucleusRunData.Arrival arrival = it.next();
            if (arrival.expiresAt <= now) {
                it.remove();
                continue;
            }
            if (!arrival.key.equals(key)) {
                continue;
            }
            long taken = Math.min(arrival.qty, left);
            arrival.qty -= taken;
            left -= taken;
            if (arrival.qty <= 0) {
                it.remove();
            }
        }
        return left;
    }
}
