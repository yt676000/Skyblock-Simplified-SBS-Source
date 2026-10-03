/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.milestone;

import sbs.modid.client.core.tab.TabWidgets;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The "Your Milestone" line Hypixel publishes in the party-stats tab widget, next to Team Deaths,
 * Team Damage Dealt and Team Healing Done.
 *
 * <p>Both halves are kept exactly as the widget words them. The value is a glyph ("❽"), not a number
 * to recompute, and the progress line is already phrased the way it should read ("7.1% to ❹");
 * rewriting either would only make them harder to match against the widget they came from.
 *
 * <p>Not gated on being in a dungeon: the widget appears where Hypixel decides to show it, so its
 * presence on the tab list is the condition. That also makes it work anywhere else the same widget
 * turns up without needing to know the place.
 */
public final class MilestoneTracker {

    private static final MilestoneTracker INSTANCE = new MilestoneTracker();

    private static final long SCAN_INTERVAL_MS = 1_000L;
    /** Drop the value once the widget has been gone this long (the run ended). */
    private static final long HIDE_AFTER_MS = 15_000L;

    /** "Your Milestone: ❽" - anything after the colon is the value. */
    private static final Pattern MILESTONE =
            Pattern.compile("(?i)^\\s*(?:your\\s+)?milestone\\s*:\\s*(.+?)\\s*$");
    /** The bracketed progress line that follows it: "(7.1% to ❹)". */
    private static final Pattern PROGRESS = Pattern.compile("^\\s*\\((.+)\\)\\s*$");

    private volatile String milestone;
    private volatile String progress;
    private volatile long seenAt;

    private long lastScanAt;

    private MilestoneTracker() {
    }

    public static MilestoneTracker getInstance() {
        return INSTANCE;
    }

    /** Called every client tick; throttles itself. */
    public void onClientTick() {
        long now = System.currentTimeMillis();
        if (now - lastScanAt < SCAN_INTERVAL_MS) {
            return;
        }
        lastScanAt = now;

        List<String> lines = TabWidgets.lines();
        String found = null;
        String foundProgress = null;
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = MILESTONE.matcher(lines.get(i));
            if (!m.matches()) {
                continue;
            }
            found = m.group(1);
            // The progress sits on the next non-empty line, in brackets. Absent while at the last
            // milestone, which is why it is optional rather than part of the same pattern.
            for (int j = i + 1; j < lines.size(); j++) {
                String next = lines.get(j).trim();
                if (next.isEmpty()) {
                    continue;
                }
                Matcher p = PROGRESS.matcher(next);
                if (p.matches()) {
                    foundProgress = p.group(1).trim();
                }
                break;
            }
            break;
        }

        if (found != null) {
            milestone = found;
            progress = foundProgress;
            seenAt = now;
        } else if (seenAt != 0 && now - seenAt > HIDE_AFTER_MS) {
            milestone = null;
            progress = null;
            seenAt = 0;
        }
    }

    /** The milestone as the widget words it, or {@code null} when it is not being shown. */
    public String milestone() {
        return fresh() ? milestone : null;
    }

    /** The progress to the next one ("7.1% to ❹"), or {@code null}. */
    public String progress() {
        return fresh() ? progress : null;
    }

    private boolean fresh() {
        return seenAt != 0 && System.currentTimeMillis() - seenAt <= HIDE_AFTER_MS;
    }

    /** Lower-cased helper kept for symmetry with the other tab readers. */
    static String lower(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }
}
