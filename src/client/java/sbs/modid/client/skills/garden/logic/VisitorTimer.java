/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.garden.logic;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.alert.AlertChannels;
import sbs.modid.client.core.alert.Alerts;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.tab.TabWidgets;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * When the next Garden visitor arrives, and whether the queue is full - from the Visitors tab
 * widget's {@code Next Visitor:} row.
 *
 * <p><b>What the row is known to say (play-instance logs to 2026-09-26).</b> 425 sightings, every one
 * of them {@code Next Visitor: Queue Full!}, beside the header {@code Visitors: (5)}. The countdown
 * form - the row while the queue still has room - has <b>never been logged</b>. It is expected to be a
 * duration ("3m 12s", "45s", "1:30") and is parsed by shape the way the pest cooldown row is, but
 * that is UNVERIFIED: every distinct shape is logged once as {@code [SBS][Visitors] next-visitor row},
 * and an unreadable one leaves the card saying so rather than inventing a time.
 *
 * <p>The countdown ticks locally between tab updates and is re-synced whenever the row's text changes.
 */
public final class VisitorTimer {

    private static final VisitorTimer INSTANCE = new VisitorTimer();

    /** How often the tab list is read. */
    private static final long SCAN_MS = 1_000L;

    /**
     * The queue size Hypixel caps at, as observed: every "Queue Full!" in the logs sits beside
     * {@code Visitors: (5)}. Replaced by the count actually seen the next time the row says full.
     */
    static final int DEFAULT_CAP = 5;

    private static final Pattern ROW = Pattern.compile("(?i)^\\s*next visitor\\s*:\\s*(.*)$");
    private static final Pattern DURATION_PART = Pattern.compile("(?i)(\\d+)\\s*([hms])");
    private static final Pattern CLOCK = Pattern.compile("(\\d+):([0-5]\\d)");

    /** What the row says. */
    public enum Kind { FULL, COUNTING, UNREADABLE }

    /** One parsed row. {@code remainingMs} only means something for {@link Kind#COUNTING}. */
    public record Row(Kind kind, long remainingMs, String raw) {
    }

    private VisitorTimer() {
    }

    public static VisitorTimer getInstance() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------ pure parsing

    /** The {@code Next Visitor:} row among the tab lines, parsed; {@code null} when it is absent. */
    public static Row findRow(List<String> tabLines) {
        for (String line : tabLines) {
            Matcher m = ROW.matcher(line);
            if (m.matches()) {
                return parseRow(m.group(1).trim());
            }
        }
        return null;
    }

    /** The value after "Next Visitor:". */
    public static Row parseRow(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("full")) {
            return new Row(Kind.FULL, 0, value);
        }
        long ms = parseDuration(lower);
        return ms < 0 ? new Row(Kind.UNREADABLE, 0, value) : new Row(Kind.COUNTING, ms, value);
    }

    /** "1h 2m 3s" / "3m 12s" / "45s" / "1:30" in milliseconds, or {@code -1}. */
    static long parseDuration(String text) {
        Matcher clock = CLOCK.matcher(text);
        if (clock.find()) {
            return (Long.parseLong(clock.group(1)) * 60L + Long.parseLong(clock.group(2))) * 1000L;
        }
        Matcher part = DURATION_PART.matcher(text);
        long millis = 0;
        boolean any = false;
        while (part.find()) {
            any = true;
            long value = Long.parseLong(part.group(1));
            millis += switch (part.group(2).toLowerCase(Locale.ROOT)) {
                case "h" -> value * 3_600_000L;
                case "m" -> value * 60_000L;
                default -> value * 1_000L;
            };
        }
        return any ? millis : -1;
    }

    /**
     * The card's one line. Row absent = the widget is switched off (the Garden always publishes it
     * otherwise), which is said plainly rather than hiding the card and leaving the player guessing.
     */
    public static String cardText(Row row, int count, int cap, long remainingMs) {
        if (row == null) {
            return "Enable the Visitors widget (/widgets)";
        }
        String visitors = count >= 0 ? "Visitors " + count + "/" + cap : "Visitors";
        return switch (row.kind()) {
            case FULL -> "Queue full" + (count >= 0 ? " (" + count + ")" : "");
            case COUNTING -> visitors + " · next in "
                    + sbs.modid.client.skills.farming.model.FarmingText.duration(
                            Math.max(0, remainingMs));
            case UNREADABLE -> visitors + " · next: " + row.raw();
        };
    }

    // ------------------------------------------------------------------ the clock

    /**
     * The countdown between tab updates. Re-synced only when the row's TEXT changes: the tab list is
     * refreshed about once a second, and re-anchoring on every identical read would make the local
     * tick stutter back to the same second over and over.
     */
    public static final class Clock {
        private String syncedRaw;
        private long syncedRemaining;
        private long syncedAt;

        /** Feeds one read; returns whether it re-synced. */
        public boolean sync(Row row, long now) {
            if (row == null || row.kind() != Kind.COUNTING) {
                syncedRaw = null;
                return false;
            }
            if (row.raw().equals(syncedRaw)) {
                return false;
            }
            syncedRaw = row.raw();
            syncedRemaining = row.remainingMs();
            syncedAt = now;
            return true;
        }

        /** Milliseconds left at {@code now}, or {@code -1} when there is no countdown. */
        public long remaining(long now) {
            if (syncedRaw == null) {
                return -1;
            }
            return Math.max(0, syncedRemaining - (now - syncedAt));
        }
    }

    // ------------------------------------------------------------------ live state

    private final Clock clock = new Clock();
    private long lastScanAt;
    private volatile Row row;
    private volatile int count = -1;
    private volatile int cap = DEFAULT_CAP;
    private volatile boolean onGarden;
    private String loggedShape = "";

    /** Whether a non-full reading has been seen, so a queue that was ALREADY full on arrival is not news. */
    private boolean sawRoom;
    private boolean fullAlerted;

    private static SBSConfig.GardenHelpersSettings cfg() {
        return ConfigManager.getInstance().get().gardenHelpers;
    }

    private static boolean wanted() {
        var c = cfg();
        return c.visitorTimer || AlertChannels.any(c.visitorFullChannels)
                || AlertChannels.any(c.visitorArrivedChannels);
    }

    /** Client tick. */
    public void tick() {
        long now = System.currentTimeMillis();
        if (now - lastScanAt < SCAN_MS) {
            return;
        }
        lastScanAt = now;
        onGarden = wanted() && SkyBlockLocation.onIsland("Garden");
        if (!onGarden) {
            reset();
            return;
        }
        List<String> lines = TabWidgets.lines();
        Row read = findRow(lines);
        int readCount = VisitorShoppingList.waitingCount(lines);
        logShape(read);
        clock.sync(read, now);

        if (read != null && read.kind() == Kind.FULL && readCount > 0) {
            cap = readCount;
        }
        int before = count;
        count = readCount;
        row = read;

        if (readCount >= 0 && before >= 0 && readCount > before) {
            send(cfg().visitorArrivedChannels, "New Garden visitor",
                    "Visitors " + readCount + "/" + cap);
        }
        boolean full = read != null && read.kind() == Kind.FULL;
        if (!full && read != null) {
            sawRoom = true;
            fullAlerted = false;
        } else if (full && sawRoom && !fullAlerted) {
            fullAlerted = true;
            send(cfg().visitorFullChannels, "Visitor queue full", "Serve someone");
        }
    }

    private void reset() {
        row = null;
        count = -1;
        clock.sync(null, 0);
        sawRoom = false;
        fullAlerted = false;
    }

    private static void send(int channels, String title, String detail) {
        if (AlertChannels.any(channels)) {
            Alerts.send(Alerts.Alert.of(title, detail), channels);
        }
    }

    /** One log line per distinct shape, digits blanked, so the countdown format gets pinned down. */
    private void logShape(Row read) {
        String shape = read == null ? "<absent>" : read.raw().replaceAll("\\d", "#");
        if (!shape.equals(loggedShape)) {
            loggedShape = shape;
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Visitors] next-visitor row '{}' -> {}",
                    read == null ? "<absent>" : read.raw(), read == null ? "absent" : read.kind());
        }
    }

    // ------------------------------------------------------------------ for the card

    public boolean onGarden() {
        return onGarden;
    }

    /** The last parsed row, {@code null} when the widget is not on the tab list. */
    public Row row() {
        return row;
    }

    /** Waiting visitors from the widget header, or {@code -1}. */
    public int count() {
        return count;
    }

    public int cap() {
        return cap;
    }

    /** Milliseconds until the next visitor, ticking locally, or {@code -1}. */
    public long remainingMs() {
        return clock.remaining(System.currentTimeMillis());
    }
}
