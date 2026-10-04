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
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ultrasequencer, the decisions only: which slot holds which number this round, and which one is
 * next. Fed one plain board per frame, so the Server Scanner recordings drive it in tests.
 *
 * <p><b>The board (CONFIRMED, Server Scanner 2026-10-04, Metaphysical).</b> The board area is the
 * rectangle of coloured (non-black) glass panes; the frame is black panes. Each round deals k
 * tiles at new random slots inside it. A tile's number is its display name AND its stack size
 * (bone meal "1" x1, light blue dye "2" x2, lapis "3" x3 ... "14"). Outside the board, one slot
 * holds the instruction item: glowstone "Remember the pattern!" while the tiles are shown, then a
 * clock "Timer: Ns" whose <i>stack size is the seconds left</i> (4, 5, 6 ...) - which is why a
 * stack-size fallback read the clock as "tile 4" and wiped the memory every second of input.
 *
 * <p><b>Rules.</b> A number is read only from a purely numeric display name, only inside the board
 * area. While glowstone shows (or before any instruction item exists), the visible tiles ARE the
 * round: whenever a tile shows that the memory does not hold, the memory is replaced by what is
 * visible. The deal is a single burst, so a frame that lands mid-burst is corrected by the next;
 * the hide burst only removes tiles and never shrinks the memory. Once the clock shows, the memory is frozen; a
 * numbered tile that reappears then is the echo of a click (Hypixel shows a clicked tile again
 * ~150 ms later) and only confirms that click. After the last click nothing is shown until the
 * next deal, which arrives ~160 ms later together with the glowstone.
 *
 * <p><b>Tiers.</b> CONFIRMED: Metaphysical (the recording). ASSUMED: every lower tier. The board
 * area is the rectangle of coloured panes, so a smaller board is read the same way (see
 * {@code UltrasequencerResyncTest}); no slot range or tile count is assumed.
 */
final class UltrasequencerModel {

    /** Width of a chest menu row. */
    static final int WIDTH = 9;

    enum Phase { NONE, MEMORISE, INPUT }

    /** Slot -> number, as dealt this round. */
    private final Map<Integer, Integer> numbers = new LinkedHashMap<>();
    private final Set<Integer> clicked = new HashSet<>();
    private final MisclickValve valve = new MisclickValve();

    private Phase phase = Phase.NONE;
    private boolean passive;
    /** The last board area seen; kept while a frame happens to show none. */
    private int[] area;

    void reset() {
        numbers.clear();
        clicked.clear();
        valve.reset();
        phase = Phase.NONE;
        passive = false;
        area = null;
    }

    /** One frame of the menu: {@code board[slot]}, {@code null} for an empty slot. */
    void onFrame(PlainItem[] board) {
        Phase now = Phase.NONE;
        for (PlainItem item : board) {
            if (item == null) {
                continue;
            }
            if (item.isTimer()) {
                now = Phase.INPUT;
                break;
            }
            if (item.isRemember()) {
                now = Phase.MEMORISE;
            }
        }
        phase = now;
        int[] found = area(board);
        if (found != null) {
            area = found;
        }
        Map<Integer, Integer> visible = visibleTiles(board, area);
        if (now == Phase.INPUT) {
            // Frozen. A tile showing at its remembered slot is the echo of a click.
            for (Map.Entry<Integer, Integer> tile : visible.entrySet()) {
                if (tile.getValue().equals(numbers.get(tile.getKey()))) {
                    clickedThrough(tile.getValue());
                }
            }
            return;
        }
        // Replace only when something new shows: a deal brings tiles the memory does not hold, while
        // the hide burst at the end of memorising only takes tiles away and must not shrink it.
        if (!visible.isEmpty() && !numbers.entrySet().containsAll(visible.entrySet())) {
            numbers.clear();
            numbers.putAll(visible);
            clicked.clear();
            valve.reset();
            passive = false;
        }
    }

    /**
     * The board area: the bounding rectangle of the coloured (non-black) panes as
     * {@code {top, bottom, left, right}} rows/columns, or {@code null} when there are none. The
     * instruction slot below it and the black frame are outside.
     */
    static int[] area(PlainItem[] board) {
        int top = Integer.MAX_VALUE;
        int bottom = -1;
        int left = Integer.MAX_VALUE;
        int right = -1;
        for (int slot = 0; slot < board.length; slot++) {
            PlainItem item = board[slot];
            if (item != null && item.isPane() && !item.id().startsWith("black")) {
                top = Math.min(top, slot / WIDTH);
                bottom = Math.max(bottom, slot / WIDTH);
                left = Math.min(left, slot % WIDTH);
                right = Math.max(right, slot % WIDTH);
            }
        }
        return bottom < 0 ? null : new int[] {top, bottom, left, right};
    }

    static boolean inArea(int[] area, int slot) {
        int row = slot / WIDTH;
        int col = slot % WIDTH;
        return area != null && row >= area[0] && row <= area[1] && col >= area[2] && col <= area[3];
    }

    /** The numbered tiles inside the board area, slot -> number. */
    static Map<Integer, Integer> visibleTiles(PlainItem[] board) {
        return visibleTiles(board, area(board));
    }

    private static Map<Integer, Integer> visibleTiles(PlainItem[] board, int[] area) {
        Map<Integer, Integer> out = new LinkedHashMap<>();
        for (int slot = 0; slot < board.length; slot++) {
            PlainItem item = board[slot];
            if (item == null || item.isPane() || !inArea(area, slot)) {
                continue;
            }
            int number = item.numericName();
            if (number > 0) {
                out.put(slot, number);
            }
        }
        return out;
    }

    /** Hints are for the input phase only: while the tiles are shown the player cannot click yet. */
    boolean showHints() {
        return phase == Phase.INPUT;
    }

    /** Every slot of the board area (for the start cue's frame); empty before a board was seen. */
    List<Integer> areaSlots(int menuSize) {
        List<Integer> out = new ArrayList<>();
        for (int slot = 0; slot < menuSize; slot++) {
            if (inArea(area, slot)) {
                out.add(slot);
            }
        }
        return out;
    }

    Phase phase() {
        return phase;
    }

    /** True when the guard stood down for this round (it blocks nothing more). Display is unaffected. */
    boolean passive() {
        return passive;
    }

    /** Slot -> number of this round's tiles (unmodifiable). */
    Map<Integer, Integer> numbers() {
        return Collections.unmodifiableMap(numbers);
    }

    boolean isClicked(int slot) {
        return clicked.contains(slot);
    }

    /** This round's slots in click order (ascending number). */
    List<Integer> clickOrder() {
        List<Integer> sorted = new ArrayList<>(numbers.keySet());
        sorted.sort(Comparator.comparingInt(numbers::get));
        return sorted;
    }

    /** The slot to click next, or -1 when the round is done or nothing was dealt. */
    int nextSlot() {
        for (int slot : clickOrder()) {
            if (!clicked.contains(slot)) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * Marks every tile up to {@code number} clicked. Hypixel only accepts tiles in order, so a click
     * on (or the echo of) tile n means 1..n were all pressed - which resyncs the model when a click
     * slipped past the click hook instead of leaving it waiting on a tile that is long done.
     */
    private void clickedThrough(int number) {
        for (Map.Entry<Integer, Integer> tile : numbers.entrySet()) {
            if (tile.getValue() <= number) {
                clicked.add(tile.getKey());
            }
        }
    }

    /**
     * Only consulted while Block Misclicks is on; with it off the caller goes straight to
     * {@link #onClick}, so the valve and {@link #passive()} are never touched. {@code passive}
     * stops the blocking for the round, never the display.
     *
     * <p>Whether a click on {@code slot} is in order: inside the board area during input only the next
     * tile is, since any other square ends the experiment. Fails open: nothing known, a passive round, or
     * two refusals in a row (see {@link MisclickValve}) all let the click through.
     */
    boolean allowsClick(int slot) {
        int next = nextSlot();
        if (passive || valve.open() || phase != Phase.INPUT || next < 0 || !inArea(area, slot)) {
            return true;
        }
        if (slot == next) {
            valve.allowed();
            return true;
        }
        if (valve.veto(slot)) {
            passive = true;
            return true;
        }
        return false;
    }

    /** The player clicked {@code slot}: a remembered tile counts, and everything before it. */
    void onClick(int slot) {
        Integer number = numbers.get(slot);
        if (number != null && phase == Phase.INPUT) {
            clickedThrough(number);
        }
    }

    String debug() {
        return "phase=" + phase + " numbers=" + numbers + " clicked=" + clicked + " next=" + nextSlot()
                + " passive=" + passive;
    }
}
