/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.rift.logic;

import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.tab.TabWidgets;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The player's motes purse, read off the sidebar the same way the coin purse is read outside the Rift.
 *
 * <p><b>Motes are not coins and this class never pretends otherwise.</b> They are a Rift-only
 * currency with no exchange rate in either direction, so nothing here exposes a coin value and no
 * caller can ask for one - the type is a plain {@code long} of motes, and the only comparisons built
 * on it (the shop's price-per-mote ranking) are motes against motes.
 *
 * <p>Purely passive: Hypixel replaces the sidebar's {@code Purse:} row with a motes row inside the
 * Rift, so the number is already on screen. The tab list is scanned as a fallback for the frames
 * where the sidebar has not been served yet, and for a layout that moves it.
 *
 * <p>The session delta is what the motes-per-hour tracker is built on: {@link #sessionGain()} is the
 * purse now minus the purse when the visit started, which is the honest reading of "earned this
 * trip" as long as nothing is spent. Spending shows up as a negative gain rather than being guessed
 * at, because the purse is the only thing published - a spend and a loss look identical from here.
 */
public final class RiftMotes {

    private static final RiftMotes INSTANCE = new RiftMotes();

    /**
     * The sidebar's motes row. Anchored on the word so a stray number elsewhere on the sidebar can
     * never be read as the purse, and tolerant of the label being either side of the value.
     */
    private static final Pattern MOTES_LABELLED =
            Pattern.compile("(?i)motes\\s*[:=]?\\s*([\\d,]+)");
    private static final Pattern MOTES_TRAILING =
            Pattern.compile("(?i)([\\d,]+)\\s*motes");

    /** A reading older than this is stale. */
    private static final long FRESHNESS_MS = 5_000L;

    private volatile long purse = -1;
    private volatile long readAt;

    /** The purse at the start of the visit, or {@code -1} until the first reading lands. */
    private volatile long visitStart = -1;

    private RiftMotes() {
        RiftState.getInstance().register(new RiftState.Listener() {
            @Override
            public void onRiftEnter() {
                reset();
            }

            @Override
            public void onRiftExit() {
                reset();
            }
        });
    }

    public static RiftMotes getInstance() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------ queries

    /** The motes purse, or {@code -1} when nothing readable is on screen. */
    public long purse() {
        return fresh() ? purse : -1;
    }

    public boolean available() {
        return fresh() && purse >= 0;
    }

    /**
     * Motes gained since the visit started - negative after a purchase, which is correct rather than
     * clamped: the purse is all Hypixel publishes, and hiding a spend would make the rate lie.
     */
    public long sessionGain() {
        return available() && visitStart >= 0 ? purse - visitStart : 0;
    }

    /** Motes per hour for this visit, or {@code -1} before there is enough of a sample. */
    public double perHour() {
        long millis = RiftState.getInstance().visitMillis();
        if (!available() || visitStart < 0 || millis < MIN_SAMPLE_MS) {
            return -1;
        }
        return sessionGain() * 3_600_000.0 / millis;
    }

    /** Below this the rate is noise - a few seconds of orbs extrapolates to a silly number. */
    private static final long MIN_SAMPLE_MS = 30_000L;

    // ------------------------------------------------------------------ reading

    /** Re-reads the purse. Called from the client tick while in the Rift. */
    public void tick() {
        if (!RiftState.getInstance().inRift()) {
            return;
        }
        long value = -1;
        for (String line : SkyBlockLocation.sidebarLines()) {
            value = parse(line);
            if (value >= 0) {
                break;
            }
        }
        if (value < 0) {
            for (String line : TabWidgets.lines()) {
                value = parse(line);
                if (value >= 0) {
                    break;
                }
            }
        }
        if (value < 0) {
            return;
        }
        purse = value;
        readAt = System.currentTimeMillis();
        if (visitStart < 0) {
            visitStart = value;
        }
    }

    /** The motes value on one line, or {@code -1}. */
    static long parse(String line) {
        if (line == null || line.isEmpty()) {
            return -1;
        }
        Matcher matcher = MOTES_LABELLED.matcher(line);
        if (!matcher.find()) {
            matcher = MOTES_TRAILING.matcher(line);
            if (!matcher.find()) {
                return -1;
            }
        }
        try {
            return Long.parseLong(matcher.group(1).replace(",", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void reset() {
        purse = -1;
        visitStart = -1;
        readAt = 0L;
    }

    private boolean fresh() {
        return System.currentTimeMillis() - readAt < FRESHNESS_MS;
    }
}
