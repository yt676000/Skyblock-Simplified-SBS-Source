/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.puzzle.logic;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.tab.TabWidgets;
import sbs.modid.client.dungeons.puzzle.model.PuzzleEntry;
import sbs.modid.client.dungeons.puzzle.model.PuzzleStatus;
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
 * <p><b>Every pattern here is unverified.</b> Nothing in this repository reads a puzzle row, and the
 * exact wording Hypixel prints cannot be established outside a live run - the same position the
 * secrets/crypts patterns started from in {@code DungeonStateManager}. So the parse is deliberately
 * tolerant (split the row on its colon, recognise the state marker if it is one of the obvious ones,
 * otherwise {@link PuzzleStatus#UNKNOWN}) and <b>the captured section is logged verbatim once per
 * change</b> under {@code [SBS][Puzzle]}. One run in a dungeon with this shipped produces the text
 * needed to replace the guesswork with a real pattern; until then this reports what it honestly got
 * and no consumer treats an unknown state as a known one.
 *
 * <p>Reading is throttled - the tab is walked at most once every {@value #SCAN_MS} ms, which is the
 * same budget {@code DungeonStateManager} uses and is far below the per-frame scanning the feature
 * request rules out.
 */
public final class PuzzleTabReader {

    private static final PuzzleTabReader INSTANCE = new PuzzleTabReader();

    private static final long SCAN_MS = 500;

    /** "Puzzles: (3)" / "Puzzles (3)" / "Puzzles: 3" - the count is the only part relied on. */
    private static final Pattern HEADER = Pattern.compile("(?i)^Puzzles\\b:?\\s*\\(?(\\d+)\\)?");

    /** "Ice Fill: ✔" - name before the colon, state marker after it. */
    private static final Pattern ROW = Pattern.compile("^([^:]{2,40}):\\s*(.*)$");

    /** A trailing "(Someone)" on a failed row, which is how the tab names who failed it. */
    private static final Pattern BLAMED = Pattern.compile("\\(([A-Za-z0-9_]{3,16})\\)");

    private volatile List<PuzzleEntry> entries = List.of();
    private volatile int total = -1;

    private long lastScan;
    private String lastLogged = "";

    private PuzzleTabReader() {
    }

    public static PuzzleTabReader getInstance() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------
    // Read
    // ------------------------------------------------------------------

    /** Re-reads the tab if the throttle has elapsed. Cheap to call every tick. */
    public void tick() {
        long now = System.currentTimeMillis();
        if (now - lastScan < SCAN_MS) {
            return;
        }
        lastScan = now;
        scan();
    }

    /**
     * The puzzles this run has, in tab order. Empty when the section was not found - which is not
     * the same as "this run has no puzzles", and callers must not read it as such.
     */
    public List<PuzzleEntry> entries() {
        return entries;
    }

    /** The count the header printed, or {@code -1} when the header was not found. */
    public int total() {
        return total;
    }

    /** The reported state of one puzzle, or {@link PuzzleStatus#UNKNOWN} when the tab did not say. */
    public PuzzleStatus statusOf(PuzzleType type) {
        for (PuzzleEntry entry : entries) {
            if (entry.type() == type) {
                return entry.status();
            }
        }
        return PuzzleStatus.UNKNOWN;
    }

    public void reset() {
        entries = List.of();
        total = -1;
        lastLogged = "";
    }

    // ------------------------------------------------------------------
    // Parse
    // ------------------------------------------------------------------

    private void scan() {
        List<String> lines = TabWidgets.lines();
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
            entries = List.of();
            total = -1;
            return;
        }

        List<PuzzleEntry> found = new ArrayList<>();
        List<String> captured = new ArrayList<>();
        // Bounded by the header's own count: the section has no terminator worth trusting, and
        // reading "until something stops looking like a row" would happily swallow the next widget.
        for (int i = headerIndex + 1; i < lines.size() && found.size() < count; i++) {
            String line = lines.get(i);
            Matcher row = ROW.matcher(line);
            if (!row.matches()) {
                break;
            }
            captured.add(line);
            found.add(parseRow(row.group(1).trim(), row.group(2).trim()));
        }

        entries = Collections.unmodifiableList(found);
        total = count;
        logOnce(count, captured, found);
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
     */
    private static PuzzleStatus statusFrom(String state) {
        if (state.indexOf('✔') >= 0 || state.indexOf('✓') >= 0) {
            return PuzzleStatus.COMPLETE;
        }
        if (state.indexOf('✖') >= 0 || state.indexOf('✗') >= 0 || state.indexOf('✘') >= 0) {
            return PuzzleStatus.FAILED;
        }
        return PuzzleStatus.UNKNOWN;
    }

    /**
     * Logs the section verbatim whenever it changes. This is the instrument the patterns above are
     * meant to be replaced from - it prints what the tab really said next to what was made of it, so
     * a wrong guess is visible in the log rather than silently producing an empty solver.
     */
    private void logOnce(int count, List<String> captured, List<PuzzleEntry> parsed) {
        StringBuilder key = new StringBuilder().append(count);
        for (String line : captured) {
            key.append('|').append(line);
        }
        if (key.toString().equals(lastLogged)) {
            return;
        }
        lastLogged = key.toString();
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Puzzle] tab section: header count={} rows={}", count,
                captured.size());
        for (int i = 0; i < captured.size(); i++) {
            PuzzleEntry entry = parsed.get(i);
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Puzzle]   raw='{}' -> type={} status={} failedBy={}",
                    captured.get(i), entry.type(), entry.status(), entry.failedBy());
        }
        if (captured.size() != count) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Puzzle] tab header claimed {} puzzles but {} rows "
                    + "parsed - the row pattern is wrong for this layout", count, captured.size());
        }
    }
}
