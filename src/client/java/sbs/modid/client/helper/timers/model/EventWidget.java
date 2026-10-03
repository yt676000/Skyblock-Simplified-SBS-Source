/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.timers.model;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The tab list's event widget - one event at a time, the running or the next one - parsed from its
 * colour-stripped lines. {@code CONFIRMED} shape, from thousands of tab dumps in the instance logs:
 * <pre>
 * Event: Hoppity's Hunt
 * Ends In: 29h
 * </pre>
 * with {@code Starts In:} for an upcoming one, and durations {@code 4d}, {@code 29h}, {@code 1h 31m},
 * {@code 56m}. Names can be truncated by Hypixel ("Jacob's Farming ...").
 */
public final class EventWidget {

    /** What the widget said, and how many real seconds it meant when it was read. */
    public record Reading(String name, boolean running, long realSeconds) {
    }

    private static final Pattern EVENT = Pattern.compile("^Event:\\s*(.+?)\\s*$");
    private static final Pattern COUNTDOWN = Pattern.compile("^(Ends|Starts) In:\\s*((?:\\d+\\s*[dhms]\\s*)+)$");
    private static final Pattern PART = Pattern.compile("(\\d+)\\s*([dhms])");

    private EventWidget() {
    }

    /** The widget's reading, or {@code null} when the tab shows no event with a countdown. */
    public static Reading parse(List<String> tabLines) {
        if (tabLines == null) {
            return null;
        }
        for (int i = 0; i < tabLines.size(); i++) {
            String line = tabLines.get(i) == null ? "" : tabLines.get(i).trim();
            Matcher event = EVENT.matcher(line);
            if (!event.matches() || i + 1 >= tabLines.size() || tabLines.get(i + 1) == null) {
                continue;
            }
            Matcher countdown = COUNTDOWN.matcher(tabLines.get(i + 1).trim());
            if (countdown.matches()) {
                return new Reading(event.group(1), countdown.group(1).equals("Ends"),
                        seconds(countdown.group(2)));
            }
        }
        return null;
    }

    /** "1h 31m" to 5460. */
    static long seconds(String duration) {
        long total = 0;
        Matcher part = PART.matcher(duration);
        while (part.find()) {
            long n = Long.parseLong(part.group(1));
            total += switch (part.group(2).charAt(0)) {
                case 'd' -> n * 86_400;
                case 'h' -> n * 3_600;
                case 'm' -> n * 60;
                default -> n;
            };
        }
        return total;
    }

    /** Whether the widget's (possibly truncated) name is this event's tab name. */
    public static boolean names(String widgetName, String tabName) {
        if (widgetName == null || tabName == null || tabName.isEmpty()) {
            return false;
        }
        String shown = widgetName.replace("...", "").replace("…", "").trim().toLowerCase();
        String want = tabName.toLowerCase();
        return !shown.isEmpty() && (want.startsWith(shown) || shown.startsWith(want));
    }
}
