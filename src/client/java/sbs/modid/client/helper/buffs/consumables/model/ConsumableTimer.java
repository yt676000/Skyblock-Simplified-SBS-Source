/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.buffs.consumables.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One active timed buff. A plain Gson object: {@code ConsumableBook} owns every rule about it, and
 * the store persists it per account and SkyBlock profile.
 */
public final class ConsumableTimer {

    /** Where the current value came from. */
    public enum Source { TAB, MENU, CHAT, LORE_ESTIMATE }

    /** Whether the server stated the current value, or it is a local guess or carry-over. */
    public enum Confidence { SERVER, ESTIMATED }

    /** Remaining time not known (a potion gained with no duration anywhere yet). */
    public static final long UNKNOWN = -1;

    /** A trailing Roman numeral: the level of "Speed III". */
    private static final Pattern LEVEL = Pattern.compile("^(.*\\S)\\s+([IVXLC]+)$");

    public String key;
    public String displayName;
    /** Roman level as printed ("III"), or {@code null}. */
    public String level;
    /** What it gives, when the line said ("+30☘ Global Fortune"), or {@code null}. */
    public String detail;
    public ConsumableKind kind = ConsumableKind.OTHER;
    public ConsumableClock clock = ConsumableClock.ONLINE_ONLY;
    /** The timer a {@link ConsumableClock#DEPENDENT} one counts under. */
    public String parentKey;
    public boolean pausedInDungeon;

    /** Milliseconds left, or {@link #UNKNOWN}. */
    public long remainingMs = UNKNOWN;
    /** How exact {@link #remainingMs} was when last set: 1 s for "60s", an hour for "16 hours". */
    public long precisionMs = 1_000L;
    /** Wall clock of the last value read or written from a source. */
    public long syncedAt;
    public Source source = Source.CHAT;
    public Confidence confidence = Confidence.ESTIMATED;

    /** Warning thresholds (ms) already fired for this instance. */
    public List<Long> firedThresholds = new ArrayList<>();
    /** Thresholds at or above this were jumped over by a large correction and stay silent. */
    public long skipAtOrAbove = Long.MAX_VALUE;
    /** The "expired" alert for this instance has gone out. */
    public boolean expiredAlerted;
    /** Wall clock the local count reached zero, while it waits for the server's end line; 0 = not. */
    public long zeroAt;

    /** Restored from disk and not yet confirmed by a server read. Alerts wait while this is set. */
    public boolean restoredUnconfirmed;
    /** Time spent on SkyBlock since the restore, so an unconfirmable timer is released eventually. */
    public long restoredOnlineMs;

    /** Clock check: predictions made at restore, consumed by the first good server read. */
    public boolean clockCheckPending;
    public long predictedOnlineMs;
    public long predictedRealMs;
    public long offlineGapMs;

    public ConsumableTimer() {
    }

    public ConsumableTimer(String displayName, ConsumableKind kind) {
        String[] split = splitLevel(displayName);
        this.displayName = split[0];
        this.level = split[1];
        this.key = keyOf(displayName);
        this.kind = kind;
        this.clock = kind.defaultClock();
        this.pausedInDungeon = kind.pausedInDungeon();
        if (clock == ConsumableClock.DEPENDENT) {
            this.parentKey = ConsumableKind.GOD_POTION_KEY;
        }
    }

    public boolean known() {
        return remainingMs >= 0;
    }

    /** The name with its level, as the card prints it. */
    public String label() {
        return level == null ? displayName : displayName + " " + level;
    }

    /**
     * The stable key of a name: lower case, the level dropped, everything else an underscore.
     * "Speed III" and "Speed IV" are the same effect (a stronger one replaces the weaker); the
     * footer's "Cookie Buff" is the Booster Cookie.
     */
    public static String keyOf(String name) {
        if (name == null) {
            return "";
        }
        String base = splitLevel(name)[0].toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return base.equals("cookie_buff") ? ConsumableKind.COOKIE_KEY : base;
    }

    /** {@code [name, level]}; the level is {@code null} when the name does not end in a numeral. */
    public static String[] splitLevel(String name) {
        String trimmed = name == null ? "" : name.trim();
        Matcher m = LEVEL.matcher(trimmed);
        if (m.matches()) {
            return new String[] {m.group(1), m.group(2)};
        }
        return new String[] {trimmed, null};
    }
}
