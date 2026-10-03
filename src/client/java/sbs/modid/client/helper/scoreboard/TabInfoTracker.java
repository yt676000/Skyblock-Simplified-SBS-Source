/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.scoreboard;

import net.minecraft.network.chat.Component;
import sbs.modid.client.core.tab.TabWidgets;
import sbs.modid.client.skills.farming.model.FarmingText;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The labelled rows of the tab list's info widget - {@code "Bank: 1B / 100.8M"},
 * {@code "Gems: 125"} - read once and handed to whoever wants them.
 *
 * <p>These are the numbers the sidebar does <b>not</b> carry. The sidebar has your purse and your
 * bits; your bank and your gems are only ever in the tab list, behind a keypress. That is the whole
 * reason this exists, and why the Custom Scoreboard can offer them as rows at all.
 *
 * <p><b>Values are kept verbatim.</b> Hypixel already writes them the way it wants them - the bank
 * as two figures either side of a slash on a co-op profile, the gem count plain - and re-writing a
 * number we only have as text is how one ends up confidently wrong. The scoreboard's own Number
 * Format setting deliberately runs before these rows are added, for the same reason.
 *
 * <p>One scan serves every row: the widget is parsed as {@code key: value} pairs rather than with a
 * pattern per row, so adding the next one ({@code Fairy Souls}, sitting right below gems in the same
 * block) is one enum constant.
 */
public final class TabInfoTracker {

    private static final TabInfoTracker INSTANCE = new TabInfoTracker();

    /** The tab list barely changes; twice a second is already generous. */
    private static final long SCAN_INTERVAL_MS = 2_000L;

    /** Drop the values once the tab has not shown them for this long (left SkyBlock, say). */
    private static final long HIDE_AFTER_MS = 30_000L;

    /** A row of the info widget, keyed by the label Hypixel writes in front of the colon. */
    public enum Row {
        BANK("bank"),
        GEMS("gems"),
        PROFILE("profile"),
        SB_LEVEL("sb level"),
        INTEREST("interest");

        private final String key;

        Row(String key) {
            this.key = key;
        }

        /** The lower-cased label this row is found under. */
        public String key() {
            return key;
        }
    }

    private final Map<Row, String> values = new EnumMap<>(Row.class);

    /**
     * The tab's own component for each row, styles intact - for callers that want to reproduce a
     * line rather than read a value out of it. See {@link #component(Row)}.
     */
    private final Map<Row, Component> components = new EnumMap<>(Row.class);

    private volatile long dataSeenAt;
    private long lastScanAt;
    private long lastLogAt;

    private TabInfoTracker() {
    }

    public static TabInfoTracker getInstance() {
        return INSTANCE;
    }

    /** Called every client tick; throttles itself. */
    public void onClientTick() {
        long now = System.currentTimeMillis();
        if (now - lastScanAt < SCAN_INTERVAL_MS) {
            return;
        }
        lastScanAt = now;
        scanTab(now);
    }

    private void scanTab(long now) {
        // Widget lines first - the info widget is where these rows live. The footer is scanned too
        // because it costs nothing and keeps this working should Hypixel ever move the block.
        List<String> lines = new ArrayList<>(TabWidgets.lines());
        lines.addAll(TabWidgets.footerLines());

        Map<Row, String> found = new EnumMap<>(Row.class);
        for (String line : lines) {
            Row row = rowOf(line);
            if (row == null || found.containsKey(row)) {
                continue;
            }
            String value = clean(line.substring(line.indexOf(':') + 1));
            if (value != null) {
                found.put(row, value);
            }
        }

        // The components are read in their own pass rather than alongside the strings: the string
        // list is the widget lines plus the footer, and only the widget lines have components.
        Map<Row, Component> foundComponents = new EnumMap<>(Row.class);
        for (Component component : TabWidgets.components()) {
            Row row = rowOf(FarmingText.strip(component.getString()).trim());
            if (row != null && !foundComponents.containsKey(row)) {
                foundComponents.put(row, component);
            }
        }

        if (!found.isEmpty()) {
            synchronized (values) {
                values.clear();
                values.putAll(found);
                components.clear();
                components.putAll(foundComponents);
            }
            dataSeenAt = now;
        } else if (dataSeenAt != 0 && now - dataSeenAt > HIDE_AFTER_MS) {
            synchronized (values) {
                values.clear();
                components.clear();
            }
        }

        // Tuning aid: only complain when the tab clearly carries one of these rows but none could be
        // read, and never more than once every 30s.
        if (found.isEmpty() && now - lastLogAt > 30_000L) {
            for (String line : lines) {
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.contains("bank") || lower.contains("gems") || lower.contains("profile")) {
                    lastLogAt = now;
                    sbs.modid.SkyblockSimplifiedSBS.LOGGER.info("[SBS][TabInfo] unparsed tab lines={}",
                            lines);
                    break;
                }
            }
        }
    }

    /** The row a {@code "key: value"} line belongs to, or {@code null} when it is none of them. */
    private static Row rowOf(String line) {
        int colon = line.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
        for (Row row : Row.values()) {
            if (row.key().equals(key)) {
                return row;
            }
        }
        return null;
    }

    /** Drops the placeholders Hypixel uses while a value is unknown. */
    private static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty() || lower.equals("none") || lower.startsWith("loading")) {
            return null;
        }
        return trimmed;
    }

    /**
     * One row exactly as the tab list words it, or {@code null} when the tab has not shown it
     * recently - which is also the answer off SkyBlock, where the widget is not served at all.
     */
    public String value(Row row) {
        if (!fresh()) {
            return null;
        }
        synchronized (values) {
            return values.get(row);
        }
    }

    /**
     * The whole tab line for a row, exactly as the tab draws it - label, value, every colour - or
     * {@code null} when it has not been seen recently.
     *
     * <p>What a caller uses to <b>reproduce</b> a row instead of re-colouring it. The SkyBlock level
     * is written in a colour that changes with the level, so any colour picked here would be a guess
     * that goes stale; handing the server's own component through is right for colours nobody has
     * seen yet.
     */
    public Component component(Row row) {
        if (!fresh()) {
            return null;
        }
        synchronized (values) {
            return components.get(row);
        }
    }

    private boolean fresh() {
        return dataSeenAt != 0 && System.currentTimeMillis() - dataSeenAt <= HIDE_AFTER_MS;
    }
}
