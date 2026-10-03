/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.experiment.logic;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chronomatron ("Simon Says") helper.
 *
 * <p><b>Mechanic.</b> The board is a row of coloured terracotta tiles. Each round the game flashes
 * the colour sequence one tile at a time - a lit tile swaps to stained glass and carries an enchant
 * glint ({@link ItemStack#hasFoil()}) - and then the player clicks the same tiles back in order.
 * Every round replays the <i>whole</i> known sequence and appends one new flash at the end.
 *
 * <p><b>Phase detection.</b> Hypixel itself announces the input phase: while the player may click,
 * an instruction item named "Timer: ..." sits on the board. That
 * item - not a guessed timing gap, not the "(Round X)" title - is the phase signal:
 * <ul>
 *   <li><b>Timer absent</b> - the show is running. Flashes are captured with a chain-length count:
 *       the first {@code lengthAtShowStart} flashes are the replay of what is already known (they
 *       self-heal the stored prefix if a past capture was wrong), everything beyond it is new and
 *       appended. No round number needed, and a tier that appends more than one per round works
 *       unchanged.</li>
 *   <li><b>Timer present</b> - input is running. The captured sequence is highlighted back in
 *       order, every highlight staying on its tile <b>until that tile was clicked</b>, and the
 *       misclick guard vetoes any click that is not the next tile in line.</li>
 * </ul>
 * Should the timer item ever not exist (reworded tier), a quiet-gap fallback still starts the input
 * phase once a new flash was seen and the board has been dark for a moment.
 *
 * <p><b>Why capture must be armed.</b> The tile the player clicked last still glints for a moment
 * after the round completes; recording it would corrupt the next capture with a phantom first
 * flash. So each show only starts recording after the board was seen fully dark once. The one
 * exception is a freshly opened board ({@link #reset()}): nothing was clicked yet, so there is no
 * glint to wait out and arming would only cost round 1's single flash.
 *
 * <p><b>Click policing.</b> During input, only the next tile in sequence is allowed - a wrong tile
 * would end the whole experiment, so it is vetoed (when block-misclicks is on). During the show,
 * <i>no</i> click is legitimate (a premature click both risks the round and would corrupt the
 * capture with its own glint), so board clicks are vetoed there too. A safety valve lets clicks
 * through once the board has been idle for a few seconds, so a desynced solver can never lock the
 * player out of finishing a round by hand.
 */
final class ChronomatronSolver {

    private static final Pattern ROUND = Pattern.compile("\\((?:Round\\s*)?([0-9]+)");

    /**
     * Fallback only (used when no "Timer: ..." item announces the input phase): no glint for this
     * long after a NEW flash was captured = the show is over. The real signal is the timer item.
     */
    private static final long SHOW_END_GAP_MS = 500;

    /** Chest board width, so a tile's vertical neighbour is slot ±{@value}. */
    private static final int GRID_WIDTH = 9;

    private enum Phase { WATCHING, INPUT }

    /** The full colour order as far as it is known; survives across rounds and only ever grows. */
    private final List<Integer> sequence = new ArrayList<>();

    /**
     * Every board slot &rarr; the TOP slot of the vertical same-block pair it belongs to (its "button
     * anchor"); a slot with no pair maps to itself. Rebuilt each scan. Chronomatron buttons are
     * usually two identical blocks stacked on top of each other that light together and are BOTH
     * clickable, so the solver treats each pair as one button anchored on its top slot - the sequence
     * stores anchors, and a click on either half of a button counts.
     */
    private final Map<Integer, Integer> buttonAnchor = new HashMap<>();

    private Phase phase = Phase.WATCHING;
    /** How much of {@link #sequence} was already known when this show started (the replay length). */
    private int lengthAtShowStart;
    /** Flashes seen during this show, replay and new ones alike. */
    private int seenThisShow;
    /** Only record once the board was seen dark - the previous round's click glint must fade first. */
    private boolean armed;
    /** True when input runs but this round's capture failed - render nothing, veto nothing. */
    private boolean passive;

    /**
     * The fail-open half of the guard. Chronomatron had none at all: its INPUT branch refused every
     * tile but the one it expected, for as long as it expected it, with no way out - so a single
     * missed flash left the player clicking a board that would not accept anything. The javadoc
     * above already promised this ("it must never hold the player hostage"); now it is true.
     */
    private final MisclickValve valve = new MisclickValve();
    private boolean timerWasVisible;

    private int round;
    private int lastLit = -1;
    private long lastFlashAt;
    private long phaseEnteredAt = System.currentTimeMillis();
    private int clickIndex;

    /**
     * The tile flashing RIGHT NOW during the show, and its 1-based position in the sequence, so the
     * render can brightly mark the panel the instant it lights up. {@code -1} = board dark / no live
     * flash to mark.
     */
    private int showLitSlot = -1;
    private int showLitNumber;

    void reset() {
        sequence.clear();
        round = 0;
        passive = false;
        timerWasVisible = false;
        enterWatching();
        // A freshly opened board carries no click glint to wait out, so arming is pointless here -
        // and harmful: round 1's show can begin the very same tick the menu appears, and the "board
        // was seen dark once" scan then never happens before the single flash is over. That lost
        // flash left round 1 with nothing captured (passive), which is why counting only ever
        // started at round 2.
        armed = true;
    }

    /** Back to capture mode: keep the known sequence, expect it replayed plus at least one more. */
    private void enterWatching() {
        valve.reset();
        phase = Phase.WATCHING;
        lengthAtShowStart = sequence.size();
        seenThisShow = 0;
        armed = false;
        lastLit = -1;
        lastFlashAt = 0;
        clickIndex = 0;
        showLitSlot = -1;
        phaseEnteredAt = System.currentTimeMillis();
    }

    private void enterInput() {
        valve.reset();
        phase = Phase.INPUT;
        clickIndex = 0;
        showLitSlot = -1;
        phaseEnteredAt = System.currentTimeMillis();
    }

    void scan(AbstractContainerMenu menu, int upper, String title) {
        buildButtonAnchors(menu, upper);
        // The round number is only a reset hint: a DROP means a fresh game on the same screen.
        // Length or phase are never derived from it - Hypixel's relation of round number to
        // sequence length is undocumented, and the old fast path that trusted it cut shows short.
        int newRound = parseRound(title);
        if (newRound > 0 && newRound < round) {
            sequence.clear();
            enterWatching();
        }
        if (newRound > 0) {
            round = newRound;
        }

        long now = System.currentTimeMillis();
        dumpBoard(menu, upper, now);
        boolean timerVisible = timerVisible(menu, upper);
        boolean timerAppeared = timerVisible && !timerWasVisible;
        boolean timerVanished = !timerVisible && timerWasVisible;
        timerWasVisible = timerVisible;

        if (phase == Phase.INPUT) {
            // Round done (every tile clicked back) or the next show started without us
            // (timed out / solver desynced): either way the next thing on the board is a show.
            if ((clickIndex >= sequence.size() && !sequence.isEmpty()) || timerVanished) {
                passive = false;
                enterWatching();
            }
            return;   // during input the clicks themselves glint; never record then
        }

        // ---- WATCHING ----
        if (timerAppeared) {
            // Hypixel says input starts NOW. The FINAL flash of a show can share the exact tick the
            // "Timer:" item pops in, so record whatever is lit right now before switching - or the
            // newest tile is dropped every round and only recovered by next round's replay (the
            // "the last one only counts next round" bug).
            if (armed) {
                captureFlash(litTile(menu, upper), now);
            }
            // If this show still taught us nothing new, this round's capture failed - go passive
            // (no highlights, no vetoes) instead of guiding wrongly.
            passive = seenThisShow <= lengthAtShowStart || sequence.isEmpty();
            enterInput();
            return;
        }
        if (timerVisible) {
            return;   // input already running but we stayed behind (opened mid-round): sit it out
        }

        int lit = litTile(menu, upper);
        if (!armed) {
            if (lit < 0) {
                armed = true;   // board dark: the previous round's click glint has faded
            }
            return;
        }
        if (valve.open()) {
            // The player is evidently clicking while we still believe a show is running, so the
            // tiles lighting up are theirs, not Hypixel's. Recording them would write the player's
            // guesses into the sequence and mis-guide every later round of the same experiment.
            return;
        }
        captureFlash(lit, now);
        // Fallback for a board without the timer item: a new flash was seen and the board has
        // been dark long enough - the show is over.
        boolean complete = seenThisShow > lengthAtShowStart && lit < 0
                && lastFlashAt > 0 && now - lastFlashAt > SHOW_END_GAP_MS;
        if (complete) {
            passive = false;
            enterInput();
        }
    }

    /**
     * Records the tile lit this tick into the sequence, on the rising edge only ({@code lit !=
     * lastLit}): a flash inside the already-known prefix self-heals a wrong past capture, one beyond
     * it is appended. {@code lit < 0} (board dark) just clears the repeat guard and the live-flash
     * marker so the next flash - even the same colour again - is seen.
     */
    private void captureFlash(int lit, long now) {
        if (lit >= 0) {
            if (lit != lastLit) {
                if (seenThisShow < sequence.size()) {
                    sequence.set(seenThisShow, lit);
                } else {
                    sequence.add(lit);
                }
                seenThisShow++;
                lastLit = lit;
            }
            lastFlashAt = now;
            // Mark the live flash so the render can highlight this exact tile as it lights up.
            showLitSlot = lit;
            showLitNumber = seenThisShow;   // 1-based position of the flash currently on screen
        } else {
            lastLit = -1;   // gap between flashes - the same colour twice in a row needs this
            showLitSlot = -1;   // board dark: the live highlight blinks off with the flash
        }
    }

    /**
     * The board's currently lit tile, or -1 when the board is dark. Hypixel encodes a flash in one
     * of two ways depending on the board, so BOTH are accepted: an enchant glint on a colour tile,
     * OR the tile switching to stained glass while the rest stay terracotta (the colour-change
     * encoding). Requiring only the glint left some boards permanently uncaptured ("did nothing").
     */
    private static int litTile(AbstractContainerMenu menu, int upper) {
        List<Integer> foils = new ArrayList<>();
        List<Integer> glass = new ArrayList<>();
        int terracottaCount = 0;
        for (int i = 0; i < upper; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (!isTile(stack)) {
                continue;
            }
            if (stack.hasFoil()) {
                foils.add(i);
            }
            String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
            if (path.contains("stained_glass")) {
                glass.add(i);
            } else if (path.contains("terracotta")) {
                terracottaCount++;
            }
        }
        // Foil encoding. A button is often two stacked blocks that BOTH glint, so the pair is reduced
        // to its single top anchor before it counts as one flash.
        Integer foilButton = singleButton(foils);
        if (foilButton != null) {
            return foilButton;
        }
        // Colour-change encoding: the glass tile(s) standing out among terracotta are the flash -
        // again a lone tile OR one vertical pair (both halves of a button turning to glass together).
        if (terracottaCount > 0) {
            Integer glassButton = singleButton(glass);
            if (glassButton != null) {
                return glassButton;
            }
        }
        return -1;
    }

    /**
     * Reduces the currently-lit slots to a SINGLE button anchor: a lone lit tile returns itself; a
     * vertical same-column pair (two stacked halves of one button, {@value #GRID_WIDTH} apart) returns
     * its top slot; anything more (ambiguous) returns {@code null} so a miss is preferred to a
     * mis-capture. Keeps the anchor consistent whichever half a stacked button lights.
     */
    private static Integer singleButton(List<Integer> lit) {
        if (lit.size() == 1) {
            return lit.get(0);
        }
        if (lit.size() == 2) {
            int a = Math.min(lit.get(0), lit.get(1));
            int b = Math.max(lit.get(0), lit.get(1));
            if (b - a == GRID_WIDTH) {
                return a;   // directly stacked (same column, adjacent rows) = one button, top anchor
            }
        }
        return null;
    }

    /**
     * Rebuilds {@link #buttonAnchor}: the bottom half of a vertical same-block pair maps to its top
     * half; every other tile maps to itself. This is what lets a click on either half of a stacked
     * button match the anchor the sequence stored.
     */
    private void buildButtonAnchors(AbstractContainerMenu menu, int upper) {
        buttonAnchor.clear();
        for (int i = 0; i < upper; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (!isTile(stack)) {
                continue;
            }
            int above = i - GRID_WIDTH;
            if (above >= 0 && sameTile(menu, i, above)) {
                buttonAnchor.put(i, above);   // bottom half -> top half (the anchor)
            }
        }
    }

    /** The button anchor for a slot (itself, unless it is the bottom half of a stacked pair). */
    private int anchorOf(int slot) {
        return buttonAnchor.getOrDefault(slot, slot);
    }

    /** Whether two board slots hold the same block type (so they can be one stacked button). */
    private static boolean sameTile(AbstractContainerMenu menu, int a, int b) {
        if (a < 0 || b < 0 || a >= menu.getItems().size() || b >= menu.getItems().size()) {
            return false;
        }
        ItemStack sa = menu.getSlot(a).getItem();
        ItemStack sb = menu.getSlot(b).getItem();
        return isTile(sa) && isTile(sb) && sa.getItem() == sb.getItem();
    }

    /**
     * Whether {@code stack} is a playable colour tile - a dyed terracotta / glass / wool / concrete
     * block. Anything else (chrome, clocks, the timer item, enchanted decoration) must never register
     * as a flash, or a permanently-glinting decoration would jam the capture.
     */
    private static boolean isTile(ItemStack stack) {
        if (stack == null || stack.isEmpty() || ExperimentationTable.isFiller(stack)) {
            return false;
        }
        String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        return path.contains("terracotta") || path.contains("stained_glass")
                || path.contains("wool") || path.contains("concrete");
    }

    /**
     * Whether the board currently shows the "Timer: ..." instruction item - Hypixel's own signal
     * that the input phase is running (it is absent while the sequence is being flashed).
     */
    private static boolean timerVisible(AbstractContainerMenu menu, int upper) {
        for (int i = 0; i < upper; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String name = stack.getHoverName().getString().replaceAll("§.", "").trim();
            if (name.startsWith("Timer:")) {
                return true;
            }
        }
        return false;
    }

    void render(GuiGraphicsExtractor g, Font font, AbstractContainerMenu menu, int left, int top) {
        boolean input = phase == Phase.INPUT && !passive;
        boolean showing = phase == Phase.WATCHING && armed && seenThisShow > 0;
        if (!input && !showing) {
            return;
        }
        // During input: everything still to click, numbered from the next tile (1 = click now),
        // each highlight vanishing once its tile was clicked. During the show: what has flashed so
        // far, dimmed, so the order can be read back while it is still being presented.
        int from = input ? clickIndex : 0;
        int limit = input ? sequence.size() : Math.min(seenThisShow, sequence.size());
        List<Integer> drawnSlots = new ArrayList<>();
        for (int p = from; p < limit; p++) {
            int idx = sequence.get(p);
            if (idx < 0 || idx >= menu.slots.size() || drawnSlots.contains(idx)) {
                continue;   // a colour repeated later in the line keeps its EARLIEST number visible
            }
            drawnSlots.add(idx);
            Slot slot = menu.getSlot(idx);
            int x = left + slot.x;
            int y = top + slot.y;
            boolean next = input && p == clickIndex;
            int color = next ? 0xFF55FF55 : (input ? 0x9055FF55 : 0x9055CCFF);
            ExperimentationTable.outline(g, x, y, color);
            // A stacked button is one target: outline its bottom half too so the whole clickable
            // block is marked (the order number stays on the top anchor only).
            int below = idx + GRID_WIDTH;
            if (below < menu.slots.size() && sameTile(menu, idx, below)) {
                Slot b = menu.getSlot(below);
                ExperimentationTable.outline(g, left + b.x, top + b.y, color);
            }
            g.text(font, Component.literal(String.valueOf(p - from + 1)),
                    x + 1, y + 1, next ? 0xFFFFFFFF : 0xFFBBBBBB);
        }

        // Live show aid: the tile flashing RIGHT NOW gets a bright highlight with its running
        // number, drawn on top of the dimmed readback so the sequence can be followed the instant
        // each panel lights up (and it lands on the actual lit tile even for a repeated colour).
        if (showing && showLitSlot >= 0 && showLitSlot < menu.slots.size()) {
            Slot slot = menu.getSlot(showLitSlot);
            int x = left + slot.x;
            int y = top + slot.y;
            ExperimentationTable.outline(g, x, y, 0xFF55CCFF);
            g.text(font, Component.literal(String.valueOf(showLitNumber)), x + 1, y + 1, 0xFFFFFFFF);
        }
    }

    /**
     * Whether a click on board slot {@code index} is in order right now. During input only the
     * next tile of the sequence is; during the show no click is (it would corrupt the capture and
     * risk the round). A stale board (see {@link MisclickValve#idle}) always allows, and so does a second
     * refusal in a row - the
     * guard protects the round, it must never hold the player hostage.
     */
    boolean allowsClick(int index) {
        if (passive || valve.open()) {
            return true;
        }
        boolean correct;
        if (phase == Phase.INPUT) {
            // Precise guard, no staleness: the player may think as long as they like, the one
            // correct button is always clickable - either half of a stacked pair counts (anchor match).
            correct = clickIndex >= sequence.size()
                    || anchorOf(sequence.get(clickIndex)) == anchorOf(index);
        } else {
            // Show running (or about to): no click is a correct click. A board that stopped
            // flashing entirely frees the player - a desynced solver never gets to hold them.
            correct = MisclickValve.idle(System.currentTimeMillis(), phaseEnteredAt, lastFlashAt);
        }
        if (correct) {
            valve.allowed();
            return true;
        }
        // Refused. Two in a row and the solver, not the player, is what is wrong: stand down for
        // the rest of the round rather than refusing a third time. This is the only way out of a
        // show phase the timer item never ended, where the player's own clicks light tiles and so
        // keep the board looking busy for as long as they keep trying.
        return valve.veto(index);
    }

    /** Advances the click cursor when the correct tile was pressed. */
    void onClick(int index) {
        if (phase == Phase.INPUT && !passive && clickIndex < sequence.size()
                && anchorOf(sequence.get(clickIndex)) == anchorOf(index)) {
            clickIndex++;
        }
    }

    private long lastDumpAt;

    /**
     * Throttled diagnostic (~every 1.5s): the board's colour tiles with their block type, foil and
     * name. This is what confirms how a flash is actually encoded on the live board - read it in the
     * instance log if the helper still misreads a sequence.
     */
    private void dumpBoard(AbstractContainerMenu menu, int upper, long now) {
        if (now - lastDumpAt < 1500) {
            return;
        }
        lastDumpAt = now;
        StringBuilder tiles = new StringBuilder();
        for (int i = 0; i < upper; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (!isTile(stack)) {
                continue;
            }
            String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
            tiles.append(i).append(':').append(path).append(stack.hasFoil() ? "*FOIL" : "").append(' ');
        }
        sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Chronomatron] phase={} seen={} lit={} tiles=[{}]",
                phase, seenThisShow, litTile(menu, upper), tiles.toString().trim());
    }

    String debug() {
        return "round=" + round + " phase=" + phase + " passive=" + passive + " valveOpen=" + valve.open() + " armed=" + armed
                + " seen=" + seenThisShow + "/" + lengthAtShowStart + " seq=" + sequence.size()
                + " click=" + clickIndex + " litSlot=" + showLitSlot + " timer=" + timerWasVisible;
    }

    private static int parseRound(String title) {
        Matcher m = ROUND.matcher(title);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }
}
