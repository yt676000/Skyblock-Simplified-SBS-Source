/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.farming.session;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One finished farming session, as stored in the profile's history. A plain mutable class so Gson
 * reads and writes it without adapters; every field defaults so an older file with fewer fields
 * still loads.
 *
 * <p>Nothing here is counted by this feature. Every number is a <b>difference</b> between what the
 * existing trackers reported when the session started and when it ended: crops from the Collection
 * Tracker, pests from Pest Profit, rare drops from the farming drop tracker.
 */
public final class FarmingSessionRecord {

    public long startedAt;
    public long endedAt;
    public long activeMs;
    public String endReason = "";

    /** Crop display name -> items gained, from the Collection Tracker's exact counter. */
    public Map<String, Long> crops = new LinkedHashMap<>();
    public long cropCoins;
    /** A crop gained this session had no Bazaar price, so {@link #cropCoins} is a lower bound. */
    public boolean cropUnpriced;

    public int pestKills;
    public long pestCoins;

    /** Rare drop name -> count. The drop chat lines are UNVERIFIED (never seen in a log). */
    public Map<String, Integer> drops = new LinkedHashMap<>();
    public long dropCoins;
    public boolean dropUnpriced;

    public long totalCrops() {
        long sum = 0;
        for (long n : crops.values()) {
            sum += n;
        }
        return sum;
    }

    public int totalDrops() {
        int sum = 0;
        for (int n : drops.values()) {
            sum += n;
        }
        return sum;
    }

    public long totalCoins() {
        return cropCoins + pestCoins + dropCoins;
    }

    /** Coins per hour of active farming, or 0 for a session too short to say (under a minute). */
    public double coinsPerHour() {
        return activeMs < 60_000L ? 0 : totalCoins() * 3_600_000.0 / activeMs;
    }

    // ------------------------------------------------------------------ diff vs previous

    /**
     * The change from {@code previous} to {@code now}, for a summary line: {@code "+1,200 (+12%)"},
     * {@code "-300 (-4%)"}, {@code "±0"}, or {@code "new"} when the previous value was zero.
     * {@code ""} when there is no previous session.
     */
    public static String delta(double now, Double previous) {
        return delta(now, previous, FarmingSessionRecord::grouped);
    }

    /** {@link #delta(double, Double)} with the amount formatted by {@code format} (e.g. 1.2M). */
    public static String delta(double now, Double previous, java.util.function.DoubleFunction<String> format) {
        if (previous == null) {
            return "";
        }
        double change = now - previous;
        if (Math.abs(change) < 0.5) {
            return "±0";
        }
        String sign = change > 0 ? "+" : "-";
        String amount = sign + format.apply(Math.abs(change));
        if (Math.abs(previous) < 0.5) {
            return amount + " (new)";
        }
        long percent = Math.round(change * 100.0 / Math.abs(previous));
        return amount + " (" + (percent > 0 ? "+" : "") + percent + "%)";
    }

    private static String grouped(double value) {
        return String.format(Locale.US, "%,d", Math.round(value));
    }
}
