/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.dev.scanner;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What {@code summary.txt} says about a session: counts per channel, the entity names seen most,
 * kills, pickups, markers and what was suppressed or throttled. Collected on the client thread as
 * the session runs, rendered once at the end. Pure, unit-tested.
 */
final class ScanSummary {

    /** Kept lists stop growing here; the summary says how many more there were. */
    static final int LIST_CAP = 200;

    private final Map<ScanChannel, Long> counts = new EnumMap<>(ScanChannel.class);
    private final Map<String, Integer> names = new HashMap<>();
    private final Map<String, Integer> suppressed = new LinkedHashMap<>();
    private final List<String> kills = new ArrayList<>();
    private final List<String> pickups = new ArrayList<>();
    private final List<String> markers = new ArrayList<>();
    private int killCount;
    private int pickupCount;

    void count(ScanChannel channel) {
        counts.merge(channel, 1L, Long::sum);
    }

    long countOf(ScanChannel channel) {
        return counts.getOrDefault(channel, 0L);
    }

    long total() {
        long sum = 0;
        for (long value : counts.values()) {
            sum += value;
        }
        return sum;
    }

    void name(String name) {
        if (name != null && !name.isEmpty()) {
            names.merge(name, 1, Integer::sum);
        }
    }

    void suppressed(String rule) {
        suppressed.merge(rule, 1, Integer::sum);
    }

    int suppressedTotal() {
        int sum = 0;
        for (int value : suppressed.values()) {
            sum += value;
        }
        return sum;
    }

    void kill(String wall, String line) {
        killCount++;
        if (kills.size() < LIST_CAP) {
            kills.add(wall + "  " + line);
        }
    }

    void pickup(String wall, String line) {
        pickupCount++;
        if (pickups.size() < LIST_CAP) {
            pickups.add(wall + "  " + line);
        }
    }

    void marker(String wall, long t, String text) {
        markers.add(wall + "  (t=" + t + " ms)  " + text);
    }

    /** The per-channel counts, for the manifest. */
    JsonObject countsJson() {
        JsonObject out = new JsonObject();
        counts.forEach((channel, value) -> out.addProperty(channel.key, value));
        return out;
    }

    JsonObject suppressedJson() {
        JsonObject out = new JsonObject();
        suppressed.forEach(out::addProperty);
        return out;
    }

    /** One short line per channel with events: {@code entity 812, sound 90}. */
    String countsLine() {
        StringBuilder out = new StringBuilder();
        counts.forEach((channel, value) -> out.append(out.isEmpty() ? "" : ", ")
                .append(channel.key).append(' ').append(value));
        return out.isEmpty() ? "none" : out.toString();
    }

    /**
     * The whole report. {@code throttled} lines come from the budget, which the summary does not own.
     */
    String render(String header, long durationMs, long bytes, List<String> throttled) {
        StringBuilder out = new StringBuilder();
        out.append(header).append('\n');
        out.append(String.format(Locale.ROOT, "Duration: %d:%02d   Events: %d   Size: %.2f MB%n",
                durationMs / 60_000, (durationMs / 1000) % 60, total(), bytes / (1024.0 * 1024.0)));

        out.append("\nEvents per channel\n");
        counts.forEach((channel, value) -> out.append(String.format(Locale.ROOT, "  %-11s %d%n", channel.key, value)));

        out.append("\nThrottled\n");
        if (throttled.isEmpty()) {
            out.append("  none - nothing was dropped\n");
        }
        throttled.forEach(line -> out.append("  ").append(line).append('\n'));

        out.append("\nSuppressed as noise (").append(suppressedTotal()).append(")\n");
        if (suppressed.isEmpty()) {
            out.append("  none\n");
        }
        suppressed.forEach((rule, value) -> out.append(String.format(Locale.ROOT, "  %-26s %d%n", rule, value)));

        out.append("\nTop entity names\n");
        List<Map.Entry<String, Integer>> top = new ArrayList<>(names.entrySet());
        top.sort((a, b) -> b.getValue() != a.getValue().intValue()
                ? Integer.compare(b.getValue(), a.getValue()) : a.getKey().compareTo(b.getKey()));
        if (top.isEmpty()) {
            out.append("  none\n");
        }
        for (int i = 0; i < Math.min(20, top.size()); i++) {
            out.append(String.format(Locale.ROOT, "  %5d  %s%n", top.get(i).getValue(), top.get(i).getKey()));
        }

        list(out, "Kills", killCount, kills);
        list(out, "Pickups", pickupCount, pickups);
        list(out, "Markers", markers.size(), markers);
        return out.toString();
    }

    private static void list(StringBuilder out, String title, int total, List<String> lines) {
        out.append('\n').append(title).append(" (").append(total).append(")\n");
        if (lines.isEmpty()) {
            out.append("  none\n");
        }
        lines.forEach(line -> out.append("  ").append(line).append('\n'));
        if (total > lines.size()) {
            out.append("  ... ").append(total - lines.size()).append(" more in the jsonl files\n");
        }
    }
}
