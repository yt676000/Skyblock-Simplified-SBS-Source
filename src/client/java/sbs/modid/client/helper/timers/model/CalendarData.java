/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.timers.model;

import sbs.modid.client.core.data.VersionedDocument;

import java.util.ArrayList;
import java.util.List;

/**
 * The event calendar's rules, as {@code timers/event_calendar.json} states them. Field names are the
 * file format. Data only: a rule says when, never what to do.
 */
public final class CalendarData implements VersionedDocument {

    public int schemaVersion = 1;
    public int dataVersion;
    public String generatedAt = "";
    public List<Event> events = new ArrayList<>();

    public static final class Event {
        /** Stable id - what the per-event toggle is stored under. Never renamed. */
        public String id = "";
        public String name = "";
        /** What the tab's {@code Event:} widget calls it (matched as a prefix: the tab truncates). */
        public String tabName = "";
        /** Lower-case mayor or minister it needs, or {@code null} for everyone. */
        public String mayor;
        /** 1-based days of the year it starts on. */
        public List<Integer> starts;
        /** 1-based days of every month it starts on. */
        public List<Integer> monthDays;
        public int startMinute;
        /** Length in SkyBlock minutes; 0 with no {@code starts}/{@code monthDays} = tab-only. */
        public int lengthMinutes;
        /** CONFIRMED or WIKI (see the file's comment). */
        public String certainty = "WIKI";
        public String note = "";

        public boolean hasRule() {
            return lengthMinutes > 0 && ((starts != null && !starts.isEmpty())
                    || (monthDays != null && !monthDays.isEmpty()));
        }
    }

    @Override
    public int schemaVersion() {
        return schemaVersion;
    }

    @Override
    public int dataVersion() {
        return dataVersion;
    }

    @Override
    public boolean valid() {
        return events != null;
    }
}
