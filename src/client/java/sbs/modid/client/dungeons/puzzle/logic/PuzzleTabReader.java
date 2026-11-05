/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.puzzle.logic;

import net.minecraft.network.chat.Component;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.dev.DevLogText;
import sbs.modid.client.core.tab.TabWidgets;
import sbs.modid.client.dungeons.events.ChatPatternRegistry;
import sbs.modid.client.dungeons.puzzle.model.PuzzleEntry;
import sbs.modid.client.dungeons.puzzle.model.PuzzleStatus;
import sbs.modid.client.dungeons.puzzle.model.PuzzleTabSection;
import sbs.modid.client.dungeons.puzzle.model.PuzzleType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the tab list's puzzle section: how many puzzles this run has, which they are, their state,
 * and who the game blamed for a failure.
 *
 * <p><b>The tab answers a different question from the room tracker and neither replaces the other.</b>
 * The tab knows which puzzles exist in the run and whether they are done - before the player has been
 * anywhere near them. The room tracker knows which puzzle is in front of you now. A solver wants both.
 *
 * <p><b>The row patterns are unverified.</b> The header {@code Puzzles: (N)} is known; the rows under
 * it have never been captured, so the parse is deliberately tolerant (split the row on its colon,
 * believe only the unambiguous state glyphs, otherwise {@link PuzzleStatus#UNKNOWN}) and the section
 * is <b>logged verbatim - plain and with its colour codes - once per distinct content</b> under
 * {@code [SBS][Puzzle]}. That log line is the capture the patterns get replaced from; until then this
 * reports what it honestly got and no consumer treats an unknown state as a known one.
 *
 * <p><b>Read on change, not on a timer.</b> The section is re-parsed only when
 * {@link TabWidgets#revision()} moves, which is once per tab packet that changed a line - so a run
 * where nothing happens costs one int comparison a tick.
 */
public final class PuzzleTabReader {


    /** "Puzzles: (3)" - the count is the only part relied on; the colon and brackets are optional. */
    private static final Pattern HEADER = Pattern.compile("(?i)^Puzzles\\b:?\\s*\\(?(\\d+)\\)?");

    /** "Ice Fill: ✔" - name before the colon, state marker after it. */
    private static final Pattern ROW = Pattern.compile("^([^:]{2,40}):\\s*(.*)$");

    /** A "(Someone)" on a failed row, which is how the tab is expected to name who failed it. */
    private static final Pattern BLAMED = Pattern.compile("\\(([A-Za-z0-9_]{3,16})\\)");

    /**
     * The three result lines CONFIRMED from 1.8.9 logs: "PUZZLE SOLVED! ... Good job!",
     * "PUZZLE FAIL! ... Yikes!" and "PUZZLE FAILED! &lt;player&gt; failed a puzzle.". None of them
     * names the puzzle, so they decide nothing here - they are logged next to the tab's state at the
     * same moment, which is the evidence needed to check the row parse against a known outcome.
     * 26.x prefixes them with {@code "> "} - see {@link PuzzleResultLine}.
     */
    static final Pattern RESULT_LINE = Pattern.compile(
            "^" + PuzzleResultLine.PREFIX + "PUZZLE (?:SOLVED|FAILED|FAIL)!");

    private volatile PuzzleTabSection section = PuzzleTabSection.ABSENT;

    private int lastRevision = Integer.MIN_VALUE;
    private String lastLogged = "";

    // Declared after every static field: the constructor registers patterns, and a field
    // initialised below this line is still null when it runs (the puzzle chat was dead for it).
    private static final PuzzleTabReader INSTANCE = new PuzzleTabReader();

    private PuzzleTabReader() {
        ChatPatternRegistry.getInstance().register(RESULT_LINE,
                (matcher, component) -> onResultLine(component), "puzzle result lines");
    }

    public static PuzzleTabReader getInstance() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------
    // Read
    // ------------------------------------------------------------------

    /** Re-parses the section if the tab changed since the last call. Client tick, inside a run. */
    public void tick() {
        int revision = TabWidgets.revision();
        if (revision == lastRevision) {
            return;
        }
        lastRevision = revision;
        List<String> lines = TabWidgets.lines();
        PuzzleTabSection parsed = parse(lines);
        if (!parsed.equals(section)) {
            section = parsed;
        }
        if (parsed.present()) {
            logOnce(parsed, lines);
        }
    }

    /** The latest section. {@link PuzzleTabSection#ABSENT} when the tab has none. Any thread. */
    public PuzzleTabSection section() {
        return section;
    }

    /**
     * The puzzles this run has, in tab order. Empty when the section was not found - which is not
     * the same as "this run has no puzzles", and callers must not read it as such.
     */
    public List<PuzzleEntry> entries() {
        return section.entries();
    }

    /** The count the header printed, or {@code -1} when the header was not found. */
    public int total() {
        return section.total();
    }

    /** The reported state of one puzzle, or {@link PuzzleStatus#UNKNOWN} when the tab did not say. */
    public PuzzleStatus statusOf(PuzzleType type) {
        return section.statusOf(type);
    }

    /** Whether the tab says this puzzle is over - solved or failed - so nothing is left to show. */
    public boolean finished(PuzzleType type) {
        PuzzleStatus status = section.statusOf(type);
        return status == PuzzleStatus.COMPLETE || status == PuzzleStatus.FAILED;
    }

    public void reset() {
        section = PuzzleTabSection.ABSENT;
        lastRevision = Integer.MIN_VALUE;
        lastLogged = "";
    }

    private void onResultLine(Component component) {
        if (!section.present()) {
            return;   // not in a run with a puzzle section; nothing to compare it with
        }
        lastRevision = Integer.MIN_VALUE;   // re-read on the next tick even if no packet came
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Puzzle] result line '{}' - tab at that moment: {}",
                DevLogText.plain(component), section.entries());
    }

    // ------------------------------------------------------------------
    // Parse - pure, so it can be tested on captured lines
    // ------------------------------------------------------------------

    /**
     * Finds the {@code Puzzles: (N)} header in widget lines (as {@link TabWidgets#lines()} returns
     * them: in display order, colour-stripped, trimmed) and parses up to N rows under it.
     *
     * <p>Bounded by the header's own count: the section has no terminator worth trusting, and
     * reading "until something stops looking like a row" would swallow {@code Team Deaths: 0}. A row
     * that does not look like {@code name: state} ends the section early.
     */
    public static PuzzleTabSection parse(List<String> lines) {
        int headerIndex = -1;
        int count = -1;
        for (int i = 0; i < lines.size(); i++) {
            Matcher header = HEADER.matcher(lines.get(i));
            if (header.find()) {
                headerIndex = i;
                count = Integer.parseInt(header.group(1));
                break;
            }
        }
        if (headerIndex < 0) {
            return PuzzleTabSection.ABSENT;
        }

        List<PuzzleEntry> found = new ArrayList<>();
        List<String> rows = new ArrayList<>();
        for (int i = headerIndex + 1; i < lines.size() && found.size() < count; i++) {
            String line = lines.get(i);
            Matcher row = ROW.matcher(line);
            if (!row.matches()) {
                break;
            }
            rows.add(line);
            found.add(parseRow(row.group(1).trim(), row.group(2).trim()));
        }
        return new PuzzleTabSection(count, Collections.unmodifiableList(found),
                Collections.unmodifiableList(rows), headerIndex + 1);
    }

    private static PuzzleEntry parseRow(String name, String state) {
        PuzzleStatus status = statusFrom(state);
        String blamed = null;
        if (status == PuzzleStatus.FAILED) {
            Matcher who = BLAMED.matcher(state);
            if (who.find()) {
                blamed = who.group(1);
            }
        }
        return new PuzzleEntry(PuzzleType.fromName(name), name, status, blamed);
    }

    /**
     * The state marker. Only the unambiguous glyphs are believed; anything else is
     * {@link PuzzleStatus#UNKNOWN} rather than assumed incomplete, because "not yet done" and "we
     * could not read this row" look identical to a player and mean opposite things to a solver.
     * {@code ✔} = complete and {@code ✦} = open are CONFIRMED (2026-10-11, maintainer's log); the
     * failed glyph ({@code ✖}?) is still unconfirmed - no failed puzzle in the captures yet.
     */
    static PuzzleStatus statusFrom(String state) {
        if (state.indexOf('✔') >= 0 || state.indexOf('✓') >= 0) {
            return PuzzleStatus.COMPLETE;
        }
        if (state.indexOf('✖') >= 0 || state.indexOf('✗') >= 0 || state.indexOf('✘') >= 0) {
            return PuzzleStatus.FAILED;
        }
        // CONFIRMED 2026-10-11: open rows read [✦], then [✔] once solved
        if (state.indexOf('✦') >= 0) {
            return PuzzleStatus.INCOMPLETE;
        }
        return PuzzleStatus.UNKNOWN;
    }

    /**
     * The probe: logs the section verbatim whenever its content changes - each row plain and in its
     * legacy {@code §} form, because a state that differs only by colour would be invisible in the
     * plain text - next to what was parsed from it. A run of this log is the capture the row patterns
     * and their tests are written from (redacted - rows can carry player names).
     *
     * <p>The window logged is the header's count of lines under it, <b>whether or not they parsed</b>:
     * a row pattern that is wrong for the real layout parses nothing, and the capture has to show
     * the lines it failed on, not an empty section.
     */
    private void logOnce(PuzzleTabSection parsed, List<String> lines) {
        int from = parsed.firstRow();
        int to = Math.min(lines.size(), from + parsed.total());
        StringBuilder key = new StringBuilder().append(parsed.total());
        for (int i = from; i < to; i++) {
            key.append('|').append(lines.get(i));
        }
        if (key.toString().equals(lastLogged)) {
            return;
        }
        lastLogged = key.toString();
        List<Component> components = TabWidgets.components();
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Puzzle] tab section: header '{}' count={} rows parsed={}",
                lines.get(from - 1), parsed.total(), parsed.rows().size());
        for (int i = from; i < to; i++) {
            int row = i - from;
            String legacy = i < components.size() ? DevLogText.legacy(components.get(i)) : null;
            if (row < parsed.entries().size()) {
                PuzzleEntry entry = parsed.entries().get(row);
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Puzzle]   raw='{}' legacy='{}' -> type={} "
                        + "status={} failedBy={}", lines.get(i), legacy, entry.type(), entry.status(),
                        entry.failedBy());
            } else {
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Puzzle]   raw='{}' legacy='{}' -> NOT PARSED",
                        lines.get(i), legacy);
            }
        }
        if (parsed.rows().size() != parsed.total()) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Puzzle] tab header claimed {} puzzles but {} rows "
                    + "parsed - the row pattern is wrong for this layout", parsed.total(),
                    parsed.rows().size());
        }
    }
}
