/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.rift.logic;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.tab.TabWidgets;
import sbs.modid.client.ui.hud.logic.HypixelHudState;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How much ф Rift Time the player has left, and how much they started the visit with.
 *
 * <p><b>Passive only.</b> Nothing here asks Hypixel anything - the number is already on screen, three
 * different ways, and this reads whichever of them the server happens to be serving.
 *
 * <p><b>Three sources, first hit wins</b>, in the order they are trusted:
 * <ol>
 *   <li>the <b>sidebar scoreboard</b>, which is the one that is always up and never scrolls away;</li>
 *   <li>the <b>action bar</b>, where Hypixel puts the Rift stat line in place of the usual defence
 *       segment - already parsed for mana, so this costs one extra scan of a string we hold anyway;</li>
 *   <li>the <b>tab list</b>, as the last resort for a layout where neither of the first two carries it.</li>
 * </ol>
 * Reading all three rather than picking one is deliberate: which of them carries the value is exactly
 * the kind of thing Hypixel moves between updates, and a feature that reads only the source that
 * happened to work on the day it was written fails silently and completely. {@link #source()} names
 * the one that answered, so the settings page can say where the number came from instead of leaving
 * a wrong-looking value unexplained.
 *
 * <p><b>The maximum is learned, not read.</b> Hypixel publishes the countdown, not the stat behind it,
 * so there is no "max" to parse. What there is: <b>you enter the Rift at full</b>, and the visit only
 * ever counts down from there apart from the pickups that top it back up. So the highest value seen
 * during this visit <i>is</i> the maximum, and it is exact from the first reading after entering. The
 * one case it is not is a visit this client joined half-way through (an F3+A, a re-log inside the
 * Rift), which is why {@link #maxCertain()} exists and the card dims the fraction when it is false
 * rather than drawing a bar against a number it made up.
 */
public final class RiftTime {

    private static final RiftTime INSTANCE = new RiftTime();

    /** The Rift Time glyph: Cyrillic small letter EF, which is what Hypixel uses for ф. */
    private static final char GLYPH = 'ф';

    /**
     * A duration next to the glyph, as either {@code m:ss} / {@code h:mm:ss} or a bare second count,
     * with the glyph on either side of it. Hypixel has shipped "ф 07:42" and "480ф" at different
     * times and in different places, and both spellings mean the same thing.
     */
    private static final Pattern GLYPH_BEFORE = Pattern.compile(
            GLYPH + "\\s*((?:\\d+:)?\\d+:\\d{2}|\\d[\\d,]*)");
    private static final Pattern GLYPH_AFTER = Pattern.compile(
            "((?:\\d+:)?\\d+:\\d{2}|\\d[\\d,]*)\\s*" + GLYPH);

    /** The same value on a labelled row, for a layout that spells it out instead of using the glyph. */
    private static final Pattern LABELLED = Pattern.compile(
            "(?i)rift\\s*time\\s*[:=]?\\s*((?:\\d+:)?\\d+:\\d{2}|\\d[\\d,]*)");

    /** A reading older than this is stale - the player left, or the source stopped carrying it. */
    private static final long FRESHNESS_MS = 5_000L;

    /** Where the live value came from. */
    public enum Source {
        NONE("nothing"),
        SCOREBOARD("the scoreboard"),
        ACTION_BAR("the action bar"),
        TAB_LIST("the tab list");

        private final String displayName;

        Source(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    private volatile int remaining = -1;
    private volatile int max = -1;
    private volatile boolean maxCertain;
    private volatile long readAt;
    private volatile Source source = Source.NONE;

    /** Throttle for the tuning log, which dumps the raw lines when nothing parses. */
    private volatile long lastMissLog;

    private RiftTime() {
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

    public static RiftTime getInstance() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------ queries

    /** Seconds left, or {@code -1} when nothing readable is on screen. */
    public int remaining() {
        return fresh() ? remaining : -1;
    }

    /**
     * The visit's starting time in seconds, or {@code -1} when nothing has been read yet. Exact when
     * {@link #maxCertain()}, otherwise the best lower bound this client has seen.
     */
    public int max() {
        return fresh() ? max : -1;
    }

    /**
     * Whether {@link #max()} is the real maximum rather than a lower bound - i.e. whether the first
     * reading of this visit happened close enough to entering to be the full value.
     */
    public boolean maxCertain() {
        return maxCertain && fresh();
    }

    /** Whether a value has been read recently enough to draw. */
    public boolean available() {
        return fresh() && remaining >= 0;
    }

    /** How much of the visit's time is left, {@code 0..1}; {@code -1} when it cannot be worked out. */
    public double fraction() {
        if (!available() || max <= 0) {
            return -1;
        }
        return Math.max(0, Math.min(1, remaining / (double) max));
    }

    /** Which source answered, for the settings status line. */
    public Source source() {
        return fresh() ? source : Source.NONE;
    }

    /** "07:42" / "1:07:42" - the countdown as it is shown. */
    public static String format(int seconds) {
        if (seconds < 0) {
            return "-";
        }
        int hours = seconds / 3600;
        int minutes = (seconds % 3600) / 60;
        int secs = seconds % 60;
        return hours > 0
                ? String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
                : String.format(Locale.US, "%d:%02d", minutes, secs);
    }

    // ------------------------------------------------------------------ reading

    /**
     * Re-reads every source. Called from the client tick while in the Rift.
     *
     * <p>The scoreboard is scanned first and the others only when it comes up empty, so the normal
     * case costs one pass over a handful of short strings.
     */
    public void tick() {
        if (!RiftState.getInstance().inRift()) {
            return;
        }
        Reading reading = readScoreboard();
        if (reading == null) {
            reading = readActionBar();
        }
        if (reading == null) {
            reading = readTabList();
        }
        if (reading == null) {
            logMiss();
            return;
        }
        accept(reading.seconds(), reading.source());
    }

    /** One parsed value and where it came from. */
    private record Reading(int seconds, Source source) {
    }

    private Reading readScoreboard() {
        for (String line : SkyBlockLocation.sidebarLines()) {
            int seconds = parse(line);
            if (seconds >= 0) {
                return new Reading(seconds, Source.SCOREBOARD);
            }
        }
        return null;
    }

    private Reading readActionBar() {
        int seconds = parse(HypixelHudState.getInstance().lastActionBar());
        return seconds < 0 ? null : new Reading(seconds, Source.ACTION_BAR);
    }

    private Reading readTabList() {
        for (String line : TabWidgets.lines()) {
            int seconds = parse(line);
            if (seconds >= 0) {
                return new Reading(seconds, Source.TAB_LIST);
            }
        }
        return null;
    }

    /**
     * The Rift Time on one line, or {@code -1}.
     *
     * <p>The glyph forms are tried before the labelled one because they are what Hypixel actually
     * ships; the labelled form only exists so a reworded layout is still readable. A bare number is
     * accepted only next to the glyph - "Motes: 1,234" is a number on a Rift line too, and reading
     * that as a time would put twenty minutes on the clock.
     */
    static int parse(String line) {
        if (line == null || line.isEmpty()) {
            return -1;
        }
        Matcher matcher = GLYPH_BEFORE.matcher(line);
        if (matcher.find()) {
            return toSeconds(matcher.group(1));
        }
        matcher = GLYPH_AFTER.matcher(line);
        if (matcher.find()) {
            return toSeconds(matcher.group(1));
        }
        matcher = LABELLED.matcher(line);
        return matcher.find() ? toSeconds(matcher.group(1)) : -1;
    }

    /** {@code "7:42"}, {@code "1:07:42"} or {@code "462"} to seconds; {@code -1} when unusable. */
    private static int toSeconds(String text) {
        try {
            String value = text.replace(",", "");
            if (!value.contains(":")) {
                return Integer.parseInt(value);
            }
            String[] parts = value.split(":");
            int seconds = 0;
            for (String part : parts) {
                seconds = seconds * 60 + Integer.parseInt(part);
            }
            return seconds;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Takes a reading and keeps the running maximum.
     *
     * <p>The maximum only moves <b>up</b>, and only within a visit: {@link #reset()} clears it on both
     * edges. It is marked certain when the very first reading of the visit lands while the visit is
     * still young - that reading is the value the player entered with. A client that joined an
     * in-progress visit reads its first value late, so the flag stays off and the card says so.
     */
    private void accept(int seconds, Source from) {
        if (seconds < 0) {
            return;
        }
        remaining = seconds;
        readAt = System.currentTimeMillis();
        source = from;
        if (seconds > max) {
            if (max < 0) {
                // The first reading of the visit. Certain only if it arrived promptly enough that
                // nothing can have drained yet - a few seconds of tick latency, not a whole minute.
                maxCertain = RiftState.getInstance().visitMillis() <= FIRST_READ_GRACE_MS;
            }
            max = seconds;
        }
    }

    /**
     * How long after entering a first reading still counts as "the value you came in with". Generous
     * enough to cover the frames where the scoreboard has not been served yet, short enough that a
     * re-log deep into a visit does not get mistaken for a fresh arrival.
     */
    private static final long FIRST_READ_GRACE_MS = 6_000L;

    private void reset() {
        remaining = -1;
        max = -1;
        maxCertain = false;
        readAt = 0L;
        source = Source.NONE;
    }

    private boolean fresh() {
        return System.currentTimeMillis() - readAt < FRESHNESS_MS;
    }

    /**
     * Dumps the candidate lines when nothing parsed, throttled, and only while the feature is on.
     *
     * <p>This is the same tuning path the vitality bar uses, and for the same reason: the failure is
     * silent (a card that simply never appears), and the fix is a one-constant change once a live
     * client has shown what the line actually looks like now. Non-ASCII characters are printed as
     * codepoints, because the glyph is exactly what would have changed.
     */
    private void logMiss() {
        long now = System.currentTimeMillis();
        if (now - lastMissLog < 10_000L
                || !ConfigManager.getInstance().get().riftTime.enabled) {
            return;
        }
        lastMissLog = now;
        List<String> lines = SkyBlockLocation.sidebarLines();
        StringBuilder sb = new StringBuilder(128);
        for (String line : lines) {
            sb.append('|').append(dumpCodepoints(line));
        }
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][RiftTime] no time on any source. sidebar={} actionbar='{}'",
                sb, dumpCodepoints(HypixelHudState.getInstance().lastActionBar()));
    }

    /** Every non-ASCII char as {@code [U+XXXX]}, so a changed glyph is identifiable from a log. */
    private static String dumpCodepoints(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            if (cp > 0x7F) {
                sb.append("[U+").append(Integer.toHexString(cp).toUpperCase(Locale.ROOT)).append(']');
            } else {
                sb.append((char) cp);
            }
        });
        return sb.toString();
    }
}
