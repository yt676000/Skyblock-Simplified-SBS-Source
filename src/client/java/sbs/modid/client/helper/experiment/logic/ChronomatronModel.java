/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.experiment.logic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Chronomatron, the decisions only: the flashed sequence and which button is next. Fed one plain
 * board per frame, so the Server Scanner recordings drive it in tests.
 *
 * <p><b>The board (CONFIRMED, Server Scanner 2026-10-04, Metaphysical).</b> Buttons are coloured
 * stained glass blocks named after their colour, usually two of the same colour stacked 9 slots
 * apart (11/20 Red ... 33/42 Purple); both halves light and both are clickable, so a button is
 * identified by its colour and anchored on its lowest slot. A flash turns the glass INTO the
 * matching terracotta with the enchant glint for ~400 ms, then back for ~500 ms; a repeated button
 * goes dark and lights again. One slot shows "Round: N" (stack size N), another the instruction
 * item: glowstone "Remember the pattern!" during the show, a clock "Timer: Ns" during input. The
 * clock appears in the same tick as the last flash. Round N shows N flashes.
 *
 * <p><b>The echo.</b> Each player click is echoed ~150 ms later (the button lights). The echo of a
 * round's LAST click arrives in the same tick as "Round: N+1" and the glowstone, i.e. at the start
 * of the next show - recording it there put a phantom first flash into every other round. So:
 * <ul>
 *   <li>a show starts only on the round boundary (glowstone appearing, or the round count
 *       changing), and a button lit within {@link #ECHO_MS} of it is that echo and is skipped;</li>
 *   <li>only rising edges (dark -> lit) after the boundary are flashes, and the show's flashes ARE
 *       the sequence (Hypixel replays the whole chain each round);</li>
 *   <li>a finished input waits for the boundary; it never re-arms on a dark board.</li>
 * </ul>
 *
 * <p><b>Tiers.</b> CONFIRMED: Metaphysical (the recording). ASSUMED: every lower tier. Nothing
 * here depends on the board size - buttons are found by colour, a button is all slots of one
 * colour (so a single unstacked block works, see {@code ChronomatronResyncTest}), the round and
 * instruction items are found by name wherever they sit. One assumption is tier-specific and
 * unverified below Metaphysical: round N shows N flashes; a show that disagrees only stops the
 * blocking, never the display.
 */
final class ChronomatronModel {

    /** A button lit this soon after the round boundary is the echo of the last click. */
    static final long ECHO_MS = 150L;

    /** A flash this soon after the clock still belongs to the show (same-tick ordering). */
    static final long LATE_FLASH_MS = 100L;

    /**
     * During input a button lighting up is the echo of a click (~150 ms after it). One that lights
     * with no click tracked within this window means the click hook missed it: the cursor follows
     * the echo instead of waiting on a button that is long done.
     */
    static final long CLICK_ECHO_MS = 400L;

    enum Phase { NONE, SHOW, INPUT, DONE }

    private final List<Integer> sequence = new ArrayList<>();
    private final List<Integer> show = new ArrayList<>();
    /** Slot -> button anchor (lowest slot of its colour), rebuilt each frame. */
    private final Map<Integer, Integer> anchors = new LinkedHashMap<>();
    private final MisclickValve valve = new MisclickValve();

    private Set<Integer> litBefore = Set.of();
    private Phase phase = Phase.NONE;
    private boolean rememberBefore;
    private boolean timerBefore;
    private int round;
    private long boundaryAt = Long.MIN_VALUE / 2;
    private long timerAt = Long.MIN_VALUE / 2;
    private long lastFlashAt;
    private long phaseAt;
    private int clickIndex;
    private boolean passive;
    private int lastLit = -1;
    private long lastClickAt = Long.MIN_VALUE / 2;

    void reset() {
        sequence.clear();
        show.clear();
        anchors.clear();
        valve.reset();
        litBefore = Set.of();
        phase = Phase.NONE;
        rememberBefore = false;
        timerBefore = false;
        round = 0;
        boundaryAt = Long.MIN_VALUE / 2;
        timerAt = Long.MIN_VALUE / 2;
        lastFlashAt = 0;
        phaseAt = 0;
        clickIndex = 0;
        passive = false;
        lastLit = -1;
        lastClickAt = Long.MIN_VALUE / 2;
    }

    /** One frame of the menu at {@code now} ms: {@code board[slot]}, {@code null} for empty. */
    void onFrame(PlainItem[] board, long now) {
        boolean remember = false;
        boolean timer = false;
        int roundNow = -1;
        Map<String, Integer> lowest = new LinkedHashMap<>();
        for (int slot = 0; slot < board.length; slot++) {
            PlainItem item = board[slot];
            if (item == null) {
                continue;
            }
            remember |= item.isRemember();
            timer |= item.isTimer();
            int r = item.nameValue("Round:");
            if (r > 0) {
                roundNow = r;
            }
            String colour = colourOf(item);
            if (colour != null) {
                lowest.putIfAbsent(colour, slot);
            }
        }
        anchors.clear();
        Set<Integer> lit = new HashSet<>();
        for (int slot = 0; slot < board.length; slot++) {
            String colour = colourOf(board[slot]);
            if (colour == null) {
                continue;
            }
            int anchor = lowest.get(colour);
            anchors.put(slot, anchor);
            if (board[slot].glint() || board[slot].id().endsWith("_terracotta")) {
                lit.add(anchor);
            }
        }

        boolean boundary = (remember && !rememberBefore) || (roundNow > 0 && roundNow != round);
        if (roundNow > 0) {
            round = roundNow;
        }
        if (boundary) {
            phase = Phase.SHOW;
            show.clear();
            boundaryAt = now;
            phaseAt = now;
            clickIndex = 0;
            passive = false;
            valve.reset();
        }
        if (timer && !timerBefore && phase == Phase.SHOW) {
            timerAt = now;
            phaseAt = now;
            phase = Phase.INPUT;
            clickIndex = 0;
            valve.reset();
            adoptShow();
        }

        for (int anchor : lit) {
            if (litBefore.contains(anchor) || now - boundaryAt < ECHO_MS) {
                continue;   // still lit, or the echo of the last click at the boundary
            }
            if (phase == Phase.SHOW) {
                show.add(anchor);
                lastFlashAt = now;
            } else if (phase == Phase.INPUT && now - timerAt <= LATE_FLASH_MS && clickIndex == 0) {
                show.add(anchor);   // the last flash landed a moment after the clock
                lastFlashAt = now;
                adoptShow();
            } else if (phase == Phase.INPUT && now - lastClickAt > CLICK_ECHO_MS
                    && clickIndex < sequence.size() && sequence.get(clickIndex) == anchor) {
                advance();   // an echo with no tracked click: the hook missed it, resync
            }
        }
        lastLit = lit.size() == 1 ? lit.iterator().next() : -1;
        litBefore = lit;
        rememberBefore = remember;
        timerBefore = timer;
    }

    /**
     * The show is the whole chain. A show that does not match the round count is not trusted for
     * blocking ({@link #passive()}); it is still displayed.
     */
    private void adoptShow() {
        sequence.clear();
        sequence.addAll(show);
        passive = sequence.isEmpty() || (round > 0 && sequence.size() != round);
    }

    /** The colour of a button block ({@code lime} for lime glass or terracotta), or {@code null}. */
    static String colourOf(PlainItem item) {
        if (item == null || item.isPane()) {
            return null;
        }
        String id = item.id();
        if (id.endsWith("_stained_glass")) {
            return id.substring(0, id.length() - "_stained_glass".length());
        }
        if (id.endsWith("_terracotta") && !id.endsWith("glazed_terracotta")) {
            return id.substring(0, id.length() - "_terracotta".length());
        }
        return null;
    }

    /**
     * Hints are for the input phase only. The show is still counted internally, but drawing it
     * read as "click these" while the game was not accepting clicks yet.
     */
    boolean showHints() {
        return phase == Phase.INPUT;
    }

    /** Every button slot on the board (for the start cue's frame). */
    java.util.Set<Integer> buttonSlots() {
        return Collections.unmodifiableSet(anchors.keySet());
    }

    Phase phase() {
        return phase;
    }

    /** True when blocking stood down for this round (guard valve, or an untrusted show). Display is unaffected. */
    boolean passive() {
        return passive;
    }

    int round() {
        return round;
    }

    /** The chain as button anchors, as of the last completed show. */
    List<Integer> sequence() {
        return Collections.unmodifiableList(sequence);
    }

    /** The flashes of the show in progress. */
    List<Integer> show() {
        return Collections.unmodifiableList(show);
    }

    int clickIndex() {
        return clickIndex;
    }

    /** The anchor lit right now, or -1. */
    int litAnchor() {
        return lastLit;
    }

    /** Every slot belonging to the button anchored at {@code anchor} (both halves). */
    List<Integer> slotsOf(int anchor) {
        List<Integer> out = new ArrayList<>(2);
        for (Map.Entry<Integer, Integer> e : anchors.entrySet()) {
            if (e.getValue() == anchor) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /** The anchor to click next, or -1. */
    int next() {
        return phase == Phase.INPUT && clickIndex < sequence.size() ? sequence.get(clickIndex) : -1;
    }

    /**
     * Only consulted while Block Misclicks is on; with it off the caller goes straight to
     * {@link #onClick}, so the valve and {@link #passive()} are never touched.
     *
     * <p>Whether a click on {@code slot} is in order. During input only the next button is; during the
     * show none is, until the board has been idle a while. Not a button, nothing known, passive, or
     * two refusals in a row: the click goes through (see {@link MisclickValve}).
     */
    boolean allowsClick(int slot, long now) {
        Integer anchor = anchors.get(slot);
        if (anchor == null || passive || valve.open() || phase == Phase.NONE || phase == Phase.DONE) {
            return true;
        }
        boolean correct = phase == Phase.INPUT
                ? clickIndex >= sequence.size() || sequence.get(clickIndex).equals(anchor)
                : MisclickValve.idle(now, phaseAt, lastFlashAt);
        if (correct) {
            valve.allowed();
            return true;
        }
        if (valve.veto(slot)) {
            passive = true;
            return true;
        }
        return false;
    }

    /** The player clicked {@code slot} at {@code now}: advances when it was the next button. */
    void onClick(int slot, long now) {
        Integer anchor = anchors.get(slot);
        if (anchor == null) {
            return;
        }
        lastClickAt = now;
        if (phase == Phase.INPUT && clickIndex < sequence.size() && sequence.get(clickIndex).equals(anchor)) {
            advance();
        }
    }

    private void advance() {
        clickIndex++;
        valve.allowed();
        if (clickIndex >= sequence.size()) {
            phase = Phase.DONE;   // wait for the round boundary, never re-arm on a dark board
        }
    }

    String debug() {
        return "round=" + round + " phase=" + phase + " passive=" + passive + " seq=" + sequence
                + " show=" + show + " click=" + clickIndex;
    }
}
