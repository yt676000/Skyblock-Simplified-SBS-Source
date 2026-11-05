/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.puzzle.logic;

import sbs.modid.client.dungeons.puzzle.model.PuzzleType;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which puzzle rooms of the current run have failed and not yet been reset, read from chat. The
 * parser and the state are pure - no Minecraft classes - so every confirmed line is unit-tested.
 *
 * <p><b>The lines</b> (CONFIRMED from the maintainer's 1.8.9 logs, colour-stripped):
 * <pre>
 * PUZZLE FAIL! &lt;player&gt; killed a Blaze in the wrong order! Yikes!   -&gt; Higher or Lower
 * PUZZLE FAIL! &lt;player&gt; lost Tic Tac Toe! Yikes!                     -&gt; Tic Tac Toe
 * PUZZLE FAIL! &lt;player&gt; couldn't find the right path! Yikes!         -&gt; room not named
 * PUZZLE FAILED! &lt;player&gt; failed a puzzle.                          -&gt; room not named
 * You used the Architect's First Draft to reset Higher Or Lower!
 * Moved 1 Architect's First Draft from your Sacks to your inventory.
 * </pre>
 * A trailing {@code (N)} is a client-side stacking counter added when the same line repeats; it is
 * stripped before matching. Every pattern is anchored at the start, so a party member quoting the
 * line ({@code Party > [MVP+] name: PUZZLE FAIL! ...}) never matches.
 *
 * <p><b>"couldn't find the right path" is deliberately not mapped to a room.</b> No spec or capture
 * in this repository says which puzzle prints it, and keying it to a guessed room would name the
 * wrong one with full confidence. It is handled like the generic line: the room comes from the tab
 * list's newly failed row or, failing that, the puzzle room the player stands in.
 */
public final class PuzzleFailWatch {

    /** A Minecraft name: what the server prints for the player. */
    private static final String PLAYER = "([A-Za-z0-9_]{1,16})";

    /** The client stacking counter: {@code " (2)"}, with or without a leftover colour code. */
    private static final Pattern COUNTER = Pattern.compile("\\s*(?:§.)?\\s*\\(\\d+\\)\\s*$");

    private static final Pattern FAIL_BLAZE = Pattern.compile(
            "^PUZZLE FAIL! " + PLAYER + " killed a Blaze in the wrong order! Yikes!$");
    private static final Pattern FAIL_TIC_TAC_TOE = Pattern.compile(
            "^PUZZLE FAIL! " + PLAYER + " lost Tic Tac Toe! Yikes!$");
    private static final Pattern FAIL_PATH = Pattern.compile(
            "^PUZZLE FAIL! " + PLAYER + " couldn['’]t find the right path! Yikes!$");
    /** Any other "PUZZLE FAIL! ... Yikes!" wording: still a failure, room unknown. */
    private static final Pattern FAIL_OTHER = Pattern.compile("^PUZZLE FAIL! " + PLAYER + " .+ Yikes!$");
    private static final Pattern FAILED_GENERIC = Pattern.compile(
            "^PUZZLE FAILED! " + PLAYER + " failed a puzzle\\.?$");
    private static final Pattern SOLVED_TIC_TAC_TOE = Pattern.compile(
            "^PUZZLE SOLVED! " + PLAYER + " tied Tic Tac Toe! Good job!$");
    private static final Pattern RESET = Pattern.compile(
            "^You used the Architect['’]s First Draft to reset (.+?)!$");
    private static final Pattern SACK_MOVE = Pattern.compile(
            "^Moved (\\d{1,4}) Architect['’]s First Draft from your Sacks to your inventory\\.?$");

    /** What one chat line means here. */
    public sealed interface Event permits Fail, Reset, Solved, SackMove {
    }

    /**
     * A failed puzzle. {@code room} is {@code null} when the line does not name it. {@code generic}
     * marks the "PUZZLE FAILED! ... failed a puzzle." summary line, which may follow a specific one.
     */
    public record Fail(String player, PuzzleType room, boolean generic) implements Event {
    }

    /** A draft was used. {@code room} is {@code null} when the name did not resolve; {@code name} is as printed. */
    public record Reset(PuzzleType room, String name) implements Event {
    }

    /** A puzzle solved by a line that names its room. */
    public record Solved(PuzzleType room) implements Event {
    }

    /** {@code count} drafts moved from the sacks into the inventory. */
    public record SackMove(int count) implements Event {
    }

    /** Ticks a fail without a room waits for the tab to name the newly failed row. */
    public static final int RESOLVE_TICKS = 40;

    /** Ticks after a named fail during which a generic line from the same player is the same failure. */
    public static final int DUPLICATE_TICKS = 60;

    private final Set<PuzzleType> failed = EnumSet.noneOf(PuzzleType.class);

    /** A fail whose room is not known yet, waiting for the tab. {@code null} when none. */
    private String pendingPlayer;
    private long pendingSince;

    /** Failures that could not be named at all; shown as "a puzzle". */
    private int unnamed;

    private String lastFailPlayer;
    private long lastFailTick = Long.MIN_VALUE / 2;

    // ------------------------------------------------------------------
    // Parse
    // ------------------------------------------------------------------

    /** The trailing stacking counter removed, or the line unchanged when it has none. */
    static String stripCounter(String line) {
        return COUNTER.matcher(line).replaceFirst("");
    }

    /** What a colour-stripped chat line means, or {@code null} when it is none of the lines above. */
    public static Event parse(String line) {
        line = PuzzleResultLine.strip(line);   // 26.x: "> PUZZLE ..." (CONFIRMED 2026-10-15)
        if (line == null || line.isEmpty()) {
            return null;
        }
        char first = line.charAt(0);
        if (first != 'P' && first != 'Y' && first != 'M') {
            return null;   // cheap reject: every line here starts with one of these
        }
        String text = stripCounter(line.trim());
        Matcher m;
        if ((m = FAIL_BLAZE.matcher(text)).matches()) {
            return new Fail(m.group(1), PuzzleType.HIGHER_OR_LOWER, false);
        }
        if ((m = FAIL_TIC_TAC_TOE.matcher(text)).matches()) {
            return new Fail(m.group(1), PuzzleType.TIC_TAC_TOE, false);
        }
        if ((m = FAIL_PATH.matcher(text)).matches()) {
            return new Fail(m.group(1), null, false);
        }
        if ((m = FAIL_OTHER.matcher(text)).matches()) {
            return new Fail(m.group(1), null, false);
        }
        if ((m = FAILED_GENERIC.matcher(text)).matches()) {
            return new Fail(m.group(1), null, true);
        }
        if (SOLVED_TIC_TAC_TOE.matcher(text).matches()) {
            return new Solved(PuzzleType.TIC_TAC_TOE);
        }
        if ((m = RESET.matcher(text)).matches()) {
            String name = m.group(1).trim();
            return new Reset(PuzzleType.fromName(name), name);
        }
        if ((m = SACK_MOVE.matcher(text)).matches()) {
            return new SackMove(Integer.parseInt(m.group(1)));
        }
        return null;
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    /** What {@link #onFail} decided. */
    public enum FailOutcome {
        /** A room newly marked failed: alert now. */
        NAMED,
        /** Room not known yet: wait for {@link #tick} to resolve it. */
        PENDING,
        /** Already known - the same failure reported twice. No alert. */
        DUPLICATE
    }

    /**
     * Records a fail at {@code tick}. A named room is marked at once; an unnamed one waits up to
     * {@link #RESOLVE_TICKS} for the tab.
     */
    public FailOutcome onFail(Fail fail, long tick) {
        boolean recentSame = fail.player().equals(lastFailPlayer) && tick - lastFailTick <= DUPLICATE_TICKS;
        if (fail.room() != null) {
            lastFailPlayer = fail.player();
            lastFailTick = tick;
            if (pendingPlayer != null && pendingPlayer.equals(fail.player())) {
                pendingPlayer = null;   // the specific line arrived after a generic one
            }
            return failed.add(fail.room()) ? FailOutcome.NAMED : FailOutcome.DUPLICATE;
        }
        if (recentSame || (pendingPlayer != null && pendingPlayer.equals(fail.player()))) {
            return FailOutcome.DUPLICATE;
        }
        lastFailPlayer = fail.player();
        lastFailTick = tick;
        pendingPlayer = fail.player();
        pendingSince = tick;
        return FailOutcome.PENDING;
    }

    /**
     * Resolves a pending fail. {@code tabNewlyFailed} is a puzzle the tab shows as failed that is not
     * yet in {@link #failed()} (or {@code null}); {@code currentRoom} is the puzzle room the player is
     * in (or {@code null}).
     *
     * @return the room it resolved to, {@link Resolution#UNNAMED} when it gave up naming one, or
     *         {@code null} when nothing was pending or it is still waiting
     */
    public Resolution tick(long tick, PuzzleType tabNewlyFailed, PuzzleType currentRoom) {
        if (pendingPlayer == null) {
            return null;
        }
        PuzzleType room = tabNewlyFailed;
        if (room == null && tick - pendingSince < RESOLVE_TICKS) {
            return null;
        }
        if (room == null && currentRoom != null && !failed.contains(currentRoom)) {
            room = currentRoom;
        }
        pendingPlayer = null;
        if (room != null) {
            failed.add(room);
            return new Resolution(room);
        }
        unnamed++;
        return Resolution.UNNAMED;
    }

    /** Where a pending fail landed. {@code room} is {@code null} for {@link #UNNAMED}. */
    public record Resolution(PuzzleType room) {
        public static final Resolution UNNAMED = new Resolution(null);
    }

    /**
     * A draft reset a room. An unresolved name clears one unnamed failure if there is one, else the
     * only failed room if there is exactly one - otherwise it cannot tell which, and clears nothing.
     */
    public void onReset(Reset reset) {
        if (reset.room() != null && failed.remove(reset.room())) {
            return;
        }
        if (unnamed > 0) {
            unnamed--;
        } else if (reset.room() == null && failed.size() == 1) {
            failed.clear();
        }
    }

    /** The room is solved - a reset room solved, or the tab showing it complete. */
    public void onSolved(PuzzleType room) {
        if (room != null) {
            failed.remove(room);
        }
    }

    /** Run end, world change, leaving the dungeon. */
    public void clear() {
        failed.clear();
        pendingPlayer = null;
        unnamed = 0;
        lastFailPlayer = null;
        lastFailTick = Long.MIN_VALUE / 2;
    }

    /** Failed rooms not yet reset, in enum order. */
    public Set<PuzzleType> failed() {
        return Collections.unmodifiableSet(failed);
    }

    public int unnamed() {
        return unnamed;
    }

    public boolean pending() {
        return pendingPlayer != null;
    }

    /** Whether there is anything to remind about. */
    public boolean active() {
        return !failed.isEmpty() || unnamed > 0;
    }

    // ------------------------------------------------------------------
    // Text
    // ------------------------------------------------------------------

    /**
     * The alert's detail line. {@code sacks} is {@code null} when the sack count is not known (no
     * sack has been read this session); {@code price} is the formatted Bazaar price, or {@code null}
     * when it should not or cannot be shown.
     */
    public static String detail(int inventory, Long sacks, String price) {
        if (sacks == null) {
            if (inventory > 0) {
                return "use an Architect's First Draft (you have " + inventory
                        + " in your inventory; sacks not read yet)";
            }
            return "use an Architect's First Draft - none in your inventory, sacks not read yet"
                    + priceSuffix(price);
        }
        long total = inventory + sacks;
        if (total <= 0) {
            return "no Architect's First Drafts" + priceSuffix(price);
        }
        return "use an Architect's First Draft (you have " + total + ": " + inventory
                + " inventory, " + sacks + " sacks)";
    }

    private static String priceSuffix(String price) {
        return price == null ? "" : " (Bazaar: " + price + ")";
    }

    /** The room as the player reads it: its name, or "a puzzle" when unknown. */
    public static String roomName(PuzzleType room) {
        return room == null ? "a puzzle" : room.displayName();
    }
}
