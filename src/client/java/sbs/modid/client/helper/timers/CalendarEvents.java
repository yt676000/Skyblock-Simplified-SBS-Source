/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.timers;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.combat.diana.logic.DianaEvent;
import sbs.modid.client.core.alert.Alerts;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.core.data.VersionedDataStore;
import sbs.modid.client.core.tab.TabWidgets;
import sbs.modid.client.helper.timers.model.CalendarData;
import sbs.modid.client.helper.timers.model.EventCalendar;
import sbs.modid.client.helper.timers.model.EventWidget;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The event calendar's rows on the Event Timers card, and its optional start alert.
 *
 * <p>Rules come from {@code timers/event_calendar.json} (bundled, versioned, no endpoint - so no
 * request); the tab's event widget, read once a second through {@link TabWidgets}, overrides the rule
 * for the one event it names ({@link EventCalendar#resolve}). Mayor-only events are gated on the
 * tab's Mayor and Minister rows, read by {@link DianaEvent#official}.
 */
public final class CalendarEvents {

    private static final CalendarEvents INSTANCE = new CalendarEvents();

    private static final String RESOURCE =
            "/assets/" + SkyblockSimplifiedSBS.MOD_ID + "/timers/event_calendar.json";
    private static final int SUPPORTED_SCHEMA = 1;
    private static final int RUNNING = 0xFF55FF55;
    private static final int UPCOMING = 0xFFFFD166;

    private final VersionedDataStore<CalendarData> store = new VersionedDataStore<>("EventCalendar", RESOURCE,
            SBSFiles.root().resolve("data").resolve("event_calendar.json"), null, CalendarData.class,
            SUPPORTED_SCHEMA);
    private boolean loaded;

    private EventWidget.Reading tab;
    private long tabReadAt;
    private String mayor = "";
    private String minister = "";
    /** Occurrences already alerted, keyed by event id and the minute it starts. */
    private final Set<String> alerted = new HashSet<>();

    private CalendarEvents() {
    }

    public static CalendarEvents getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.TimersSettings cfg() {
        return ConfigManager.getInstance().get().timers;
    }

    /** Every event in the data file, for the settings' per-event toggles. */
    public List<CalendarData.Event> events() {
        if (!loaded) {
            loaded = true;
            store.load();
        }
        CalendarData data = store.get();
        return data == null || data.events == null ? List.of() : data.events;
    }

    /** Once a second, from the timers' scan: re-read the tab, then fire a due alert. */
    void tick(long minuteOfYear) {
        List<String> lines = TabWidgets.lines();
        tab = EventWidget.parse(lines);
        tabReadAt = System.currentTimeMillis();
        mayor = DianaEvent.official(lines, DianaEvent.MAYOR_ROW);
        minister = DianaEvent.official(lines, DianaEvent.MINISTER_ROW);
        alert(minuteOfYear);
    }

    /** The tab reading, counted down since it was read. */
    private EventWidget.Reading liveTab() {
        EventWidget.Reading reading = tab;
        if (reading == null) {
            return null;
        }
        long elapsed = (System.currentTimeMillis() - tabReadAt) / 1000;
        return new EventWidget.Reading(reading.name(), reading.running(), Math.max(0, reading.realSeconds() - elapsed));
    }

    private boolean inOffice(String name) {
        return name != null && (name.equals(mayor) || name.equals(minister));
    }

    /** The resolved rows for the enabled events that apply, soonest first (running ones first). */
    List<EventCalendar.Row> resolved(long minuteOfYear) {
        SBSConfig.TimersSettings cfg = cfg();
        EventWidget.Reading reading = liveTab();
        List<EventCalendar.Row> rows = new ArrayList<>();
        for (CalendarData.Event event : events()) {
            if (cfg.calendarHidden.contains(event.id)) {
                continue;
            }
            if (event.mayor != null && !inOffice(event.mayor)
                    && !(reading != null && EventWidget.names(reading.name(), event.tabName))) {
                continue;   // a mayor event while that mayor is out of office, unless the tab says otherwise
            }
            EventCalendar.Row row = EventCalendar.resolve(event, minuteOfYear, reading);
            if (row != null) {
                rows.add(row);
            }
        }
        rows.sort(Comparator.comparing((EventCalendar.Row r) -> !r.running())
                .thenComparingLong(EventCalendar.Row::realSeconds));
        return rows;
    }

    /** The card rows. */
    List<TimerEntry> rows(long minuteOfYear) {
        SBSConfig.TimersSettings cfg = cfg();
        List<TimerEntry> out = new ArrayList<>();
        for (EventCalendar.Row row : resolved(minuteOfYear)) {
            if (out.size() >= cfg.calendarRows) {
                break;
            }
            String time = EventTimers.shortDuration(row.realSeconds());
            String value = (row.measured() ? "" : "~") + (row.running() ? "ends " + time : time);
            out.add(new TimerEntry(row.event().name, value,
                    row.measured() ? TimerEntry.Certainty.MEASURED : TimerEntry.Certainty.DERIVED,
                    row.running() ? RUNNING : UPCOMING));
        }
        if (cfg.calendarMayorEvents) {
            for (CalendarData.Event event : events()) {
                if (event.mayor != null && !inOffice(event.mayor) && !cfg.calendarHidden.contains(event.id)
                        && out.size() < cfg.calendarRows + 3) {
                    out.add(new TimerEntry(event.name, "if " + capitalised(event.mayor) + " is elected",
                            TimerEntry.Certainty.UNKNOWN));
                }
            }
        }
        return out;
    }

    private void alert(long minuteOfYear) {
        SBSConfig.TimersSettings cfg = cfg();
        if (!cfg.showCalendar || cfg.calendarAlertMinutes <= 0 || minuteOfYear < 0) {
            return;
        }
        long window = cfg.calendarAlertMinutes * 60L;
        for (EventCalendar.Row row : resolved(minuteOfYear)) {
            if (row.running() || row.realSeconds() > window) {
                continue;
            }
            // The occurrence's start, in real seconds from the epoch rounded to the minute: stable across
            // ticks, different for the next occurrence, so each start is announced once.
            long startsAt = (System.currentTimeMillis() / 1000 + row.realSeconds()) / 60;
            if (alerted.add(row.event().id + "@" + startsAt) && alerted.add(row.event().id + "@" + (startsAt - 1))) {
                Alerts.send(Alerts.Alert.of(row.event().name + " starts in "
                                + EventTimers.shortDuration(row.realSeconds()),
                        row.event().name + " starts in " + EventTimers.shortDuration(row.realSeconds())),
                        cfg.calendarAlertChannels);
            }
        }
        if (alerted.size() > 200) {
            alerted.clear();
        }
    }

    private static String capitalised(String name) {
        return name.isEmpty() ? name : name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
    }
}
