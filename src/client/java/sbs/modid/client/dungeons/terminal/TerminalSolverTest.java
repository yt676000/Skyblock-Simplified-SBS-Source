/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.terminal;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import sbs.modid.client.dungeons.terminal.TerminalSolutions.Hint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Runs the terminal solvers against boards built here, so every terminal can be checked without a
 * dungeon run - the part of this feature that is otherwise only testable four floors into F7.
 *
 * <p>The boards are <b>real {@link ChestMenu}s</b> filled with real item stacks, not a stand-in
 * model, and they go through the very same {@link TerminalSolutions#solve} the live solver calls. A
 * case therefore fails for exactly the reasons the real thing would. The menus are never opened and
 * never become the player's container, so nothing is ever sent to the server.
 *
 * <p>Each case states the answer it expects slot by slot, and the interesting ones are the traps:
 * an already-selected item carries the glint and must be left alone, {@code light_blue_wool} must
 * not answer a "blue" terminal, and the rubix board's cheapest colour is checked against a
 * brute-force minimum rather than against the solver's own opinion.
 *
 * <p><b>What a green melody case does and does not prove.</b> Its board is built to the encoding the
 * solver assumes (purple panes marking the column, a green note travelling towards it, a button per
 * row). Passing means the solver reads that layout correctly - it cannot confirm that Hypixel's
 * melody terminal really is laid out that way. That one still wants a look at a real board, for
 * which the terminal dump in the log is the tool.
 */
public final class TerminalSolverTest {

    /** One finished case: what was expected, what the solver said, and the board to look at. */
    public record Result(String name, String title, boolean passed, String detail,
                         TerminalType type, AbstractContainerMenu menu, int upper,
                         Map<Integer, Hint> hints, Map<Integer, Hint> expected) {

        /** Cases without a board (title classification) render as a line of text only. */
        public boolean hasBoard() {
            return menu != null;
        }
    }

    private TerminalSolverTest() {
    }

    /** Runs every case in order. Needs a player (the boards borrow their inventory), else empty. */
    public static List<Result> runAll() {
        List<Result> results = new ArrayList<>();
        if (Minecraft.getInstance().player == null) {
            return results;
        }
        results.add(classification());
        results.add(order());
        results.add(panes());
        results.add(colourWithLightDecoy());
        results.add(startsWith());
        results.add(rubixFixed());
        results.add(rubixOptimum());
        results.add(melodyAligned());
        results.add(melodyWaiting());
        return results;
    }

    public static String summary(List<Result> results) {
        int passed = 0;
        for (Result result : results) {
            if (result.passed()) {
                passed++;
            }
        }
        return passed + "/" + results.size();
    }

    // ------------------------------------------------------------------
    // Cases
    // ------------------------------------------------------------------

    /** The six real GUI titles have to land on the six types - and only melody may skip the guard. */
    private static Result classification() {
        Map<String, TerminalType> titles = new LinkedHashMap<>();
        titles.put("Click in order!", TerminalType.ORDER);
        titles.put("Select all the RED items!", TerminalType.COLOR);
        titles.put("What starts with 'L'?", TerminalType.STARTS_WITH);
        titles.put("Correct all the panes!", TerminalType.PANES);
        titles.put("Change all to same color!", TerminalType.RUBIX);
        titles.put("Click the button on time!", TerminalType.MELODY);
        StringBuilder failures = new StringBuilder();
        for (Map.Entry<String, TerminalType> entry : titles.entrySet()) {
            TerminalType found = TerminalType.classify(entry.getKey());
            if (found != entry.getValue()) {
                failures.append('"').append(entry.getKey()).append("\" -> ").append(found)
                        .append(" (want ").append(entry.getValue()).append(") ");
            }
        }
        if (TerminalType.MELODY.guardsMisclicks()) {
            failures.append("melody must not block clicks ");
        }
        if (!TerminalType.RUBIX.guardsMisclicks() || !TerminalType.ORDER.guardsMisclicks()) {
            failures.append("rubix/order should block wrong clicks ");
        }
        boolean passed = failures.isEmpty();
        return custom("Titles → type", passed,
                passed ? titles.size() + " titles, guard flags correct" : failures.toString().trim());
    }

    /**
     * "Click in order!": 1..5 already green, so the answer is the pane holding 6 - and the pane
     * holding 7 as the unclickable look-ahead behind it.
     */
    private static Result order() {
        Board board = new Board(3);
        for (int number = 1; number <= 14; number++) {
            board.set(number - 1, pane(number <= 5 ? DyeColor.LIME : DyeColor.RED, number));
        }
        return check("Click in order!", "Click in order!", board,
                Map.of(5, new Hint(TerminalSolutions.CLICK, "6"),
                        6, TerminalSolutions.Hint.preview(TerminalSolutions.NEXT, "7")));
    }

    /** "Correct all the panes!": every red pane, and none of the green ones. */
    private static Result panes() {
        Board board = new Board(3);
        int[] red = {0, 3, 7, 11};
        for (int slot = 0; slot < 15; slot++) {
            board.set(slot, pane(DyeColor.GREEN, 1));
        }
        for (int slot : red) {
            board.set(slot, pane(DyeColor.RED, 1));
        }
        Map<Integer, Hint> expected = new LinkedHashMap<>();
        for (int slot : red) {
            expected.put(slot, new Hint(TerminalSolutions.CLICK, null));
        }
        return check("Correct all the panes!", "Correct all the panes!", board, expected);
    }

    /**
     * "Select all the BLUE items!" with the two traps that matter: a light blue item, which is a
     * different colour and must not be marked, and a blue one that already carries the glint.
     */
    private static Result colourWithLightDecoy() {
        Board board = new Board(3);
        board.set(0, wool(DyeColor.BLUE));
        board.set(1, new ItemStack(Items.DYE.pick(DyeColor.BLUE)));
        board.set(2, new ItemStack(Items.DYED_TERRACOTTA.pick(DyeColor.BLUE)));
        board.set(3, wool(DyeColor.LIGHT_BLUE));                             // a different colour entirely
        board.set(4, new ItemStack(Items.STAINED_GLASS.pick(DyeColor.BLUE))); // not a pane: still clickable
        board.set(5, glint(wool(DyeColor.BLUE)));                            // already selected
        board.set(6, wool(DyeColor.RED));
        Map<Integer, Hint> expected = new LinkedHashMap<>();
        for (int slot : new int[]{0, 1, 2, 4}) {
            expected.put(slot, new Hint(TerminalSolutions.CLICK, null));
        }
        return check("Select all the BLUE items!", "Select all the BLUE items!", board, expected);
    }

    /** "What starts with 'L'?" - named here, so the case does not depend on the client language. */
    private static Result startsWith() {
        Board board = new Board(3);
        board.set(0, named(Items.LILY_PAD, "Lily Pad"));
        board.set(1, named(Items.LAVA_BUCKET, "Lava Bucket"));
        board.set(2, named(Items.EMERALD, "Emerald"));
        board.set(3, glint(named(Items.LEATHER_HELMET, "Leather Helmet"))); // already selected
        board.set(4, named(Items.LADDER, "Ladder"));
        Map<Integer, Hint> expected = new LinkedHashMap<>();
        for (int slot : new int[]{0, 1, 4}) {
            expected.put(slot, new Hint(TerminalSolutions.CLICK, null));
        }
        return check("What starts with 'L'?", "What starts with 'L'?", board, expected);
    }

    /**
     * "Change all to same color!" on a board worked out by hand: three red, one orange, one blue.
     * Red costs two clicks (orange back one, blue forward one); every other colour costs at least
     * five. So the answer is red, and the orange tile has to be a right click.
     */
    private static Result rubixFixed() {
        Board board = new Board(3);
        board.set(0, wool(DyeColor.RED));
        board.set(1, wool(DyeColor.RED));
        board.set(2, wool(DyeColor.RED));
        board.set(3, wool(DyeColor.ORANGE));
        board.set(4, wool(DyeColor.BLUE));
        Map<Integer, Hint> expected = new LinkedHashMap<>();
        expected.put(3, new Hint(TerminalSolutions.RIGHT_CLICK, "-1"));
        expected.put(4, new Hint(TerminalSolutions.CLICK, "1"));
        return check("Rubix: hand-worked board", "Change all to same color!", board, expected);
    }

    /**
     * The same terminal on a random board, checked against a brute force rather than against a
     * hand-written answer: the plan the solver hands out must cost exactly as many clicks as the
     * best of the five colours, and no tile may be sent the long way round.
     */
    private static Result rubixOptimum() {
        DyeColor[] cycle = {DyeColor.RED, DyeColor.ORANGE, DyeColor.YELLOW, DyeColor.GREEN, DyeColor.BLUE};
        Random random = new Random(7); // fixed seed: a failing case can be looked at again
        Board board = new Board(3);
        int[] colours = new int[15];
        for (int slot = 0; slot < colours.length; slot++) {
            colours[slot] = random.nextInt(cycle.length);
            board.set(slot, wool(cycle[colours[slot]]));
        }
        int cheapest = Integer.MAX_VALUE;
        for (int target = 0; target < cycle.length; target++) {
            int clicks = 0;
            for (int colour : colours) {
                int forward = Math.floorMod(target - colour, cycle.length);
                clicks += Math.min(forward, cycle.length - forward);
            }
            cheapest = Math.min(cheapest, clicks);
        }
        String title = "Change all to same color!";
        Map<Integer, Hint> hints = TerminalSolutions.solve(TerminalType.RUBIX, title, board.menu, board.upper);
        int planned = 0;
        boolean longWayRound = false;
        for (Hint hint : hints.values()) {
            int clicks = Math.abs(Integer.parseInt(hint.label()));
            planned += clicks;
            if (clicks > cycle.length / 2) {
                longWayRound = true;
            }
        }
        boolean passed = planned == cheapest && !longWayRound;
        return new Result("Rubix: cheapest colour", title, passed,
                passed ? planned + " clicks, the brute-force minimum"
                        : "solver wants " + planned + " clicks, minimum is " + cheapest
                        + (longWayRound ? " (and sends a tile the long way round)" : ""),
                TerminalType.RUBIX, board.menu, board.upper, hints, Map.of());
    }

    /** Melody with the note standing on the marked column: the row's button, ready to press. */
    private static Result melodyAligned() {
        Board board = melodyBoard(4);
        return check("Melody: note on the marked column", "Click the button on time!", board,
                Map.of(18, new Hint(TerminalSolutions.CLICK, "!")));
    }

    /** The same board with the note two columns short: the same button, but not yet. */
    private static Result melodyWaiting() {
        Board board = melodyBoard(2);
        return check("Melody: note still travelling", "Click the button on time!", board,
                Map.of(18, new Hint(TerminalSolutions.WAIT, null)));
    }

    /**
     * A melody board: purple panes above and below column 4 mark where a note has to be, the note
     * row carries its button in column 0, and the note itself sits in {@code noteColumn}.
     */
    private static Board melodyBoard(int noteColumn) {
        Board board = new Board(6);
        board.set(9 + 4, pane(DyeColor.MAGENTA, 1));    // row 1, column 4
        board.set(27 + 4, pane(DyeColor.MAGENTA, 1));   // row 3, column 4
        board.set(18, new ItemStack(Items.STONE_BUTTON)); // row 2's button
        for (int column = 1; column < 9; column++) {
            board.set(18 + column, pane(DyeColor.WHITE, 1));
        }
        board.set(18 + noteColumn, pane(DyeColor.LIME, 1));
        return board;
    }

    // ------------------------------------------------------------------
    // Running and comparing
    // ------------------------------------------------------------------

    /**
     * Solves the board and compares slot by slot. A slot may be expected with a {@code null} label,
     * which means "mark it, the text is not the point"; the outline colour is always compared, since
     * that is what tells a left click from a right one.
     */
    private static Result check(String name, String title, Board board, Map<Integer, Hint> expected) {
        TerminalType type = TerminalType.classify(title);
        Map<Integer, Hint> hints = TerminalSolutions.solve(type, title, board.menu, board.upper);
        StringBuilder detail = new StringBuilder();
        for (Map.Entry<Integer, Hint> entry : expected.entrySet()) {
            Hint got = hints.get(entry.getKey());
            if (got == null) {
                detail.append("slot ").append(entry.getKey()).append(" not marked; ");
                continue;
            }
            if (got.color() != entry.getValue().color()) {
                detail.append("slot ").append(entry.getKey()).append(" wrong colour; ");
            }
            // Checked on its own rather than left to the colour: this flag is what the misclick guard
            // reads, so a mark that looks right but is clickable when it should not be must fail here.
            if (got.preview() != entry.getValue().preview()) {
                detail.append("slot ").append(entry.getKey())
                        .append(got.preview() ? " is a preview, want clickable; "
                                : " is clickable, want preview; ");
            }
            String want = entry.getValue().label();
            if (want != null && !want.equals(got.label())) {
                detail.append("slot ").append(entry.getKey()).append(" says '").append(got.label())
                        .append("', want '").append(want).append("'; ");
            }
        }
        for (Integer slot : hints.keySet()) {
            if (!expected.containsKey(slot)) {
                detail.append("slot ").append(slot).append(" marked but should not be; ");
            }
        }
        boolean passed = detail.isEmpty();
        return new Result(name, title, passed,
                passed ? expected.size() + " slots, exactly as expected" : detail.toString().trim(),
                type, board.menu, board.upper, hints, expected);
    }

    private static Result custom(String name, boolean passed, String detail) {
        return new Result(name, "", passed, detail, TerminalType.NONE, null, 0, Map.of(), Map.of());
    }

    // ------------------------------------------------------------------
    // Board building
    // ------------------------------------------------------------------

    /** A real chest menu over a container we fill ourselves - never opened, never sent anywhere. */
    private static final class Board {

        private final SimpleContainer container;
        private final AbstractContainerMenu menu;
        private final int upper;

        Board(int rows) {
            Inventory inventory = Minecraft.getInstance().player.getInventory();
            this.container = new SimpleContainer(rows * 9);
            this.menu = rows == 3 ? ChestMenu.threeRows(0, inventory, container)
                    : ChestMenu.sixRows(0, inventory, container);
            this.upper = rows * 9;
        }

        void set(int slot, ItemStack stack) {
            container.setItem(slot, stack);
        }
    }

    /** A stained-glass pane, stacked - the count is the number an "in order" terminal shows. */
    private static ItemStack pane(DyeColor colour, int count) {
        return new ItemStack(Items.STAINED_GLASS_PANE.pick(colour), count);
    }

    private static ItemStack wool(DyeColor colour) {
        return new ItemStack(Items.WOOL.pick(colour));
    }

    /** The glint an already-selected terminal item carries, without needing an enchantment. */
    private static ItemStack glint(ItemStack stack) {
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        return stack;
    }

    private static ItemStack named(Item item, String name) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return stack;
    }
}
