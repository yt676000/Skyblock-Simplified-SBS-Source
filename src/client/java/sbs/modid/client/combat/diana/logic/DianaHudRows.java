/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.logic;

import sbs.modid.client.combat.diana.model.DianaHudLine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns one card's ordered lines and a snapshot of the event into the rows the card draws.
 *
 * <p>Pure: no Minecraft, no config, no clock. The HUD, the line editor's preview and the settings
 * preview all build their rows here, which is what makes the preview show what the card will
 * actually say, and what lets every rule below be tested without a client.
 *
 * <ul>
 *   <li>Lines are emitted in the order given; a line with nothing to say adds no row.</li>
 *   <li>{@link DianaHudLine#PER_CREATURE} adds at most {@code perCreatureCap} rows, and stops when
 *       the card reaches {@link #MAX_ROWS} - the ceiling the card always had.</li>
 *   <li>{@link DianaHudLine#NO_SHURIKEN} on the same card as {@link DianaHudLine#CREATURE_HEALTH} is
 *       drawn under each creature, as it always was; on a card without it, it lists the
 *       un-starred creatures as rows of its own.</li>
 *   <li>An empty result means the card is not drawn at all.</li>
 * </ul>
 */
public final class DianaHudRows {

    /** The per-creature block never grows a card past this many rows. */
    public static final int MAX_ROWS = 14;

    /** A chain nearer than this to expiring is worth colouring. */
    static final long CHAIN_WARN_MS = 5 * 60 * 1000L;

    /** How a row's value is coloured; the renderer owns the actual colours. */
    public enum Tone {
        MUTED,
        ACCENT,
        WARN,
        OK,
        /** The creature's own colour, carried on the row. */
        CREATURE
    }

    /** One drawn row: label left, value right. */
    public record Row(String label, String value, Tone tone, int rgb) {

        static Row of(String label, String value, Tone tone) {
            return new Row(label, value, tone, 0);
        }
    }

    /** A rare creature in sight - your own sighting; shared ones carry no health and are not rows. */
    public record Creature(String label, String health, int rgb, boolean starred) {
    }

    /**
     * Everything the lines read. {@code trackerOn} is the tracker setting: without it the session
     * lines have no numbers and draw nothing, as before.
     */
    public record Data(int chainsRunning, long oldestChainRemainingMs, boolean trackerOn,
                       long burrows, long creatures, long treasures, long sinceInquisitor,
                       long sinceKing, Map<String, Long> creatureCounts, List<Creature> inSight) {

        public Data {
            creatureCounts = creatureCounts == null ? Map.of() : creatureCounts;
            inSight = inSight == null ? List.of() : List.copyOf(inSight);
        }
    }

    private DianaHudRows() {
    }

    public static List<Row> build(List<DianaHudLine> lines, Data data, int perCreatureCap) {
        List<Row> rows = new ArrayList<>();
        boolean shurikenUnderHealth = lines.contains(DianaHudLine.CREATURE_HEALTH)
                && lines.contains(DianaHudLine.NO_SHURIKEN);
        for (DianaHudLine line : lines) {
            switch (line) {
                case CHAINS -> {
                    if (data.chainsRunning() > 0) {
                        rows.add(Row.of("Chains", String.valueOf(data.chainsRunning()),
                                data.oldestChainRemainingMs() < CHAIN_WARN_MS ? Tone.WARN : Tone.OK));
                    }
                }
                case OLDEST_CHAIN -> {
                    if (data.chainsRunning() > 0) {
                        rows.add(Row.of("Oldest", clock(data.oldestChainRemainingMs()), Tone.MUTED));
                    }
                }
                case BURROWS -> session(rows, data, "Burrows", data.burrows());
                case CREATURES -> session(rows, data, "Creatures", data.creatures());
                case TREASURES -> session(rows, data, "Treasures", data.treasures());
                case SINCE_INQUISITOR -> {
                    if (data.trackerOn() && data.creatures() > 0) {
                        rows.add(Row.of("Since inq", String.valueOf(data.sinceInquisitor()), Tone.ACCENT));
                    }
                }
                case SINCE_KING -> {
                    if (data.trackerOn() && data.creatures() > 0) {
                        rows.add(Row.of("Since king", String.valueOf(data.sinceKing()), Tone.ACCENT));
                    }
                }
                case PER_CREATURE -> {
                    if (!data.trackerOn()) {
                        break;
                    }
                    int added = 0;
                    for (Map.Entry<String, Long> entry : data.creatureCounts().entrySet()) {
                        if (added >= perCreatureCap || rows.size() >= MAX_ROWS) {
                            break;
                        }
                        rows.add(Row.of(entry.getKey(), String.valueOf(entry.getValue()), Tone.MUTED));
                        added++;
                    }
                }
                case CREATURE_HEALTH -> {
                    for (Creature creature : data.inSight()) {
                        rows.add(new Row(creature.label(), creature.health(), Tone.CREATURE,
                                creature.rgb()));
                        if (shurikenUnderHealth && !creature.starred()) {
                            rows.add(Row.of("  no shuriken", "", Tone.WARN));
                        }
                    }
                }
                case NO_SHURIKEN -> {
                    if (shurikenUnderHealth) {
                        break;
                    }
                    for (Creature creature : data.inSight()) {
                        if (!creature.starred()) {
                            rows.add(Row.of(creature.label(), "no shuriken", Tone.WARN));
                        }
                    }
                }
                default -> {
                }
            }
        }
        return rows;
    }

    private static void session(List<Row> rows, Data data, String label, long value) {
        if (data.trackerOn()) {
            rows.add(Row.of(label, String.valueOf(value), Tone.MUTED));
        }
    }

    /** "12:04" - minutes and seconds, which is how a half-hour chain timer is read. */
    static String clock(long millis) {
        long seconds = Math.max(0L, millis) / 1000L;
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
}
