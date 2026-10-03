/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.terminal;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import sbs.modid.SkyblockSimplifiedSBS;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out what to click in an open F7 / M7 terminal. Pure functions over the menu's slots: given a
 * {@link TerminalType} and the board, each solver returns the slots to mark and how - the solver
 * class around it only reads the menu and draws the answer, and <b>nothing here ever clicks</b>.
 *
 * <p>Every terminal is re-solved from scratch on each client tick rather than tracked as a sequence,
 * because the board itself carries the whole state: a clicked item gets the enchantment glint, an
 * ordered pane turns green, a colour is simply what it is. That makes the helper immune to a missed
 * packet or a click that never landed - what you see is what the server last said.
 */
public final class TerminalSolutions {

    /**
     * Slot advice: the outline colour, an optional short label drawn on the slot, and whether the
     * mark is only a <b>look-ahead</b> rather than something to click now.
     *
     * <p>The distinction is not cosmetic. The misclick guard lets through exactly what the solution
     * marks, so a slot shown purely to say "this one is next" has to be told apart from one that may
     * be clicked - otherwise pointing two moves ahead would quietly permit clicking two moves ahead,
     * which on the ordered terminal is precisely the mistake the guard exists to stop.
     */
    public record Hint(int color, String label, boolean preview) {

        public Hint(int color, String label) {
            this(color, label, false);
        }

        static Hint of(int color) {
            return new Hint(color, null, false);
        }

        /** A slot marked only so the mouse can already travel there. Never clickable. */
        static Hint preview(int color, String label) {
            return new Hint(color, label, true);
        }
    }

    /** Click this now. */
    public static final int CLICK = 0xFF55FF55;
    /** Right-click this - the rubix cycle is shorter backwards. */
    public static final int RIGHT_CLICK = 0xFFFFAA00;
    /** Your button, but not yet: melody's note has not reached the marked column. */
    public static final int WAIT = 0xFF55AAFF;
    /** The click after the current one - shown to aim at, deliberately dimmer than {@link #CLICK}. */
    public static final int NEXT = 0xFF2E8B57;

    /**
     * The 16 dye colours, <b>longest name first</b>. Matching in this order is what keeps
     * {@code light_blue_wool} from being read as "blue" - the first hit wins, so the compound names
     * have to be asked about before the plain ones they contain.
     */
    private static final String[] COLOURS = {"light_blue", "light_gray", "magenta", "yellow", "orange",
            "purple", "brown", "green", "black", "white", "blue", "cyan", "gray", "lime", "pink", "red"};

    /** The rubix terminal's cycle: one left click steps forwards, one right click steps backwards. */
    private static final String[] CYCLE = {"red", "orange", "yellow", "green", "blue"};

    private static final Pattern STARTS_WITH = Pattern.compile("(?i)starts?\\s*with:?\\s*'?([A-Za-z0-9])");
    private static final Pattern CLICK_THE = Pattern.compile("(?i)click\\s+the\\s+(.+?)[!?.]*$");

    private TerminalSolutions() {
    }

    /** The slots to mark for this terminal, keyed by slot index. Empty when there is nothing to say. */
    public static Map<Integer, Hint> solve(TerminalType type, String title, AbstractContainerMenu menu,
                                           int upper) {
        return switch (type) {
            case ORDER -> order(menu, upper);
            case COLOR -> colour(title, menu, upper);
            case STARTS_WITH -> startsWith(title, menu, upper);
            case PANES -> panes(menu, upper);
            case RUBIX -> rubix(menu, upper);
            case MELODY -> melody(menu, upper);
            case ITEM_NAME -> itemName(title, menu, upper);
            case NONE -> Map.of();
        };
    }

    // ------------------------------------------------------------------
    // The six terminals
    // ------------------------------------------------------------------

    /**
     * "Click in order!" - the numbered panes carry their number as the stack count, and a pane that
     * has been clicked turns green. The next click is therefore the lowest number still red.
     *
     * <p>Anything green is skipped, but a pane of <i>no</i> recognised colour still counts: the state
     * that matters is "done or not", and reading it off the green alone survives Hypixel swapping the
     * open panes to some other block.
     *
     * <p><b>The number after it is marked too</b>, dimmer and as a {@link Hint#preview}. This terminal
     * is not solved by knowing which pane is next - that part is trivial - but by how fast the mouse
     * gets there, and the travel can only start early if the target is already on screen. The preview
     * stays unclickable, so seeing the next move never turns into being allowed to make it early.
     */
    private static Map<Integer, Hint> order(AbstractContainerMenu menu, int upper) {
        List<OrderSlot> slots = new ArrayList<>();
        for (int i = 0; i < upper; i++) {
            ItemStack stack = item(menu, i);
            if (stack.isEmpty()) {
                continue;
            }
            slots.add(new OrderSlot(i, paneColour(stack), stack.getCount(), stack.hasFoil()));
        }

        Map<Integer, Integer> targets = orderTargets(slots);
        if (targets.isEmpty()) {
            return Map.of();
        }
        Map<Integer, Hint> hints = new LinkedHashMap<>();
        boolean first = true;
        for (Map.Entry<Integer, Integer> entry : targets.entrySet()) {
            hints.put(entry.getKey(), first
                    ? new Hint(CLICK, String.valueOf(entry.getValue()))
                    : Hint.preview(NEXT, String.valueOf(entry.getValue())));
            first = false;
        }
        return hints;
    }

    /** The colour of the panes that carry a number. The frame is a different colour, and that is the point. */
    private static final String NUMBER_PANE = "red";

    /** Highest number this terminal ever shows. */
    private static final int MAX_NUMBER = 14;

    /**
     * One slot as the order terminal sees it: no Minecraft, so the rule below can be tested.
     *
     * @param paneColour the colour out of the item <b>id</b> ({@code red_stained_glass_pane}), or
     *                   {@code null} for anything that is not a stained pane. Never read off a
     *                   rendered colour - a texture pack can change that and an id cannot
     * @param clicked    the stack carries the enchant glint, meaning this number is already done
     */
    public record OrderSlot(int slot, String paneColour, int count, boolean clicked) {
    }

    /**
     * The next number to click and the one after it, or <b>empty when the board does not add up</b>.
     *
     * <p>Split out of {@link #order} with no Minecraft in it, for the same reason the consent gate is
     * split out of the sender: this is the part that was wrong, and a rule that can only be exercised
     * by standing in F7 gets checked once and never again.
     *
     * <p><b>Two conditions, both required.</b> A slot is a number only when the item is a
     * {@value #NUMBER_PANE} pane <i>and</i> its count is in range. The count alone is what shipped,
     * and the frame is made of panes with a stack size of 1 - so every frame slot read as "number 1",
     * and the first one in slot order won, which is the top-left corner before the grid even begins.
     * Both highlights landed there instead of on the real 1.
     *
     * <p><b>A board that contradicts itself produces nothing.</b> Two panes cannot both show the same
     * number, so if that is what was parsed then the parse is wrong, and the honest output is silence.
     * A wrong highlight in a terminal is worse than no highlight: the player acts on it, and in a
     * timed room they act fast and without checking.
     *
     * <p>Deliberately not solved by restricting the search to the inner grid. The layout is stable
     * today, but a slot-region filter would hide this bug rather than fix it and would break without a
     * word the first time Hypixel moves the board. Filter by what the item <i>is</i>.
     */
    public static Map<Integer, Integer> orderTargets(List<OrderSlot> slots) {
        Map<Integer, Integer> numbers = new LinkedHashMap<>();
        Set<Integer> seen = new HashSet<>();
        boolean contradictory = false;

        for (OrderSlot slot : slots) {
            if (slot == null || !NUMBER_PANE.equals(slot.paneColour())) {
                continue;   // the frame, the filler, and anything Hypixel adds later
            }
            if (slot.count() < 1 || slot.count() > MAX_NUMBER) {
                continue;
            }
            if (!seen.add(slot.count())) {
                // Two live panes showing one number. Whatever this board is, it is not the one the
                // rule below assumes, so nothing is drawn on it.
                contradictory = true;
            }
            if (!slot.clicked()) {
                numbers.put(slot.slot(), slot.count());
            }
        }

        if (contradictory) {
            SkyblockSimplifiedSBS.LOGGER.debug(
                    "[SBS][Terminal] Click-in-order board has a duplicate number - showing nothing. "
                            + "Parsed: {}", numbers);
            return Map.of();
        }
        if (numbers.isEmpty()) {
            return Map.of();
        }

        // Lowest first, then the one after it - the click and its preview.
        List<Map.Entry<Integer, Integer>> ordered = new ArrayList<>(numbers.entrySet());
        ordered.sort(Map.Entry.comparingByValue());
        Map<Integer, Integer> result = new LinkedHashMap<>();
        result.put(ordered.get(0).getKey(), ordered.get(0).getValue());
        if (ordered.size() > 1) {
            result.put(ordered.get(1).getKey(), ordered.get(1).getValue());
        }
        return result;
    }

    /**
     * "Select all the &lt;colour&gt; items!" - 27 items of every kind, not just panes, so the colour
     * is read off the item id ({@code magenta_glazed_terracotta}) and, failing that, off its display
     * name ("Rose Red Dye"). Items already selected carry the glint.
     */
    private static Map<Integer, Hint> colour(String title, AbstractContainerMenu menu, int upper) {
        String want = colourInTitle(title);
        if (want == null) {
            return Map.of();
        }
        boolean bordered = hasBorder(upper);
        Map<Integer, Hint> hints = new LinkedHashMap<>();
        for (int i = 0; i < upper; i++) {
            ItemStack stack = item(menu, i);
            if (isChrome(stack, bordered) || stack.hasFoil()) {
                continue;
            }
            if (want.equals(colourOf(stack))) {
                hints.put(i, Hint.of(CLICK));
            }
        }
        return hints;
    }

    /** "What starts with 'C'?" - every unselected item whose display name starts with that letter. */
    private static Map<Integer, Hint> startsWith(String title, AbstractContainerMenu menu, int upper) {
        Matcher matcher = STARTS_WITH.matcher(title);
        if (!matcher.find()) {
            return Map.of();
        }
        char letter = Character.toLowerCase(matcher.group(1).charAt(0));
        boolean bordered = hasBorder(upper);
        Map<Integer, Hint> hints = new LinkedHashMap<>();
        for (int i = 0; i < upper; i++) {
            ItemStack stack = item(menu, i);
            if (isChrome(stack, bordered) || stack.hasFoil()) {
                continue;
            }
            String name = displayName(stack);
            if (!name.isEmpty() && Character.toLowerCase(name.charAt(0)) == letter) {
                hints.put(i, Hint.of(CLICK));
            }
        }
        return hints;
    }

    /**
     * "Correct all the panes!" - a board of red and green panes; every red one has to be flipped and
     * the order does not matter. The border panes are black or grey, so only "red" is ever a target.
     */
    private static Map<Integer, Hint> panes(AbstractContainerMenu menu, int upper) {
        Map<Integer, Hint> hints = new LinkedHashMap<>();
        for (int i = 0; i < upper; i++) {
            String colour = paneColour(item(menu, i));
            if ("red".equals(colour)) {
                hints.put(i, Hint.of(CLICK));
            }
        }
        return hints;
    }

    /**
     * "Change all to same color!" - the five colours form a cycle (red → orange → yellow → green →
     * blue → red); a left click steps forwards in it, a right click backwards.
     *
     * <p>The puzzle is not <i>how</i> to recolour a pane but <b>which colour to pick</b>: every
     * candidate is costed as the sum over all panes of the shorter way round, and the cheapest wins
     * (ties go to the colour that already has the most panes, which is the fewest slots to touch).
     * Each marked slot then says how many clicks it needs, negative meaning right-click.
     */
    private static Map<Integer, Hint> rubix(AbstractContainerMenu menu, int upper) {
        int[] colours = new int[upper];
        int found = 0;
        for (int i = 0; i < upper; i++) {
            colours[i] = indexInCycle(colourOf(item(menu, i)));
            if (colours[i] >= 0) {
                found++;
            }
        }
        if (found < 2) {
            return Map.of();
        }
        int target = -1;
        int bestClicks = Integer.MAX_VALUE;
        int bestAlready = -1;
        for (int candidate = 0; candidate < CYCLE.length; candidate++) {
            int clicks = 0;
            int already = 0;
            for (int colour : colours) {
                if (colour < 0) {
                    continue;
                }
                int forward = Math.floorMod(candidate - colour, CYCLE.length);
                clicks += Math.min(forward, CYCLE.length - forward);
                if (forward == 0) {
                    already++;
                }
            }
            if (clicks < bestClicks || (clicks == bestClicks && already > bestAlready)) {
                bestClicks = clicks;
                bestAlready = already;
                target = candidate;
            }
        }
        Map<Integer, Hint> hints = new LinkedHashMap<>();
        for (int i = 0; i < upper; i++) {
            if (colours[i] < 0) {
                continue;
            }
            int forward = Math.floorMod(target - colours[i], CYCLE.length);
            int backward = CYCLE.length - forward;
            if (forward == 0) {
                continue; // already the target colour: leave it alone
            }
            if (forward <= backward) {
                hints.put(i, new Hint(CLICK, String.valueOf(forward)));
            } else {
                hints.put(i, new Hint(RIGHT_CLICK, "-" + backward));
            }
        }
        return hints;
    }

    /**
     * "Click the button on time!" - the timing terminal. A green note travels along its row towards
     * the column the purple panes mark out; the button that belongs to the row is what you press,
     * and only in the moment the note stands on that column.
     *
     * <p>Read from the board's own geometry rather than from fixed slot numbers: the column carrying
     * the most purple panes is the marked one, the green panes are the notes, and a row's button is
     * whatever button or lever sits in it (the note itself if there is none). So the helper says
     * <i>which</i> button in blue and turns it green the moment the note lines up - the click stays
     * the player's, which for this terminal is the entire skill.
     */
    private static Map<Integer, Hint> melody(AbstractContainerMenu menu, int upper) {
        int columns = 9;
        if (upper < columns || upper % columns != 0) {
            return Map.of();
        }
        int[] markers = new int[columns];
        List<Integer> notes = new ArrayList<>();
        for (int i = 0; i < upper; i++) {
            String colour = paneColour(item(menu, i));
            if (colour == null) {
                continue;
            }
            if (colour.equals("magenta") || colour.equals("purple")) {
                markers[i % columns]++;
            } else if (colour.equals("green") || colour.equals("lime")) {
                notes.add(i);
            }
        }
        int hitColumn = -1;
        int mostMarkers = 1; // one stray pane is not a marked column; two of them are
        for (int column = 0; column < columns; column++) {
            if (markers[column] > mostMarkers) {
                mostMarkers = markers[column];
                hitColumn = column;
            }
        }
        Map<Integer, Hint> hints = new LinkedHashMap<>();
        for (int note : notes) {
            boolean now = hitColumn >= 0 && note % columns == hitColumn;
            int button = buttonInRow(menu, note / columns, columns, upper);
            hints.put(button >= 0 ? button : note,
                    now ? new Hint(CLICK, "!") : Hint.of(WAIT));
        }
        return hints;
    }

    /** Fallback for a "Click the &lt;item&gt;!" title: every unselected slot whose name matches. */
    private static Map<Integer, Hint> itemName(String title, AbstractContainerMenu menu, int upper) {
        Matcher matcher = CLICK_THE.matcher(title.trim());
        if (!matcher.find()) {
            return Map.of();
        }
        String want = matcher.group(1).trim().toLowerCase(Locale.ROOT);
        if (want.isEmpty()) {
            return Map.of();
        }
        boolean bordered = hasBorder(upper);
        Map<Integer, Hint> hints = new LinkedHashMap<>();
        for (int i = 0; i < upper; i++) {
            ItemStack stack = item(menu, i);
            if (isChrome(stack, bordered) || stack.hasFoil()) {
                continue;
            }
            if (displayName(stack).toLowerCase(Locale.ROOT).contains(want)) {
                hints.put(i, Hint.of(CLICK));
            }
        }
        return hints;
    }

    // ------------------------------------------------------------------
    // Board helpers
    // ------------------------------------------------------------------

    static ItemStack item(AbstractContainerMenu menu, int index) {
        ItemStack stack = menu.getSlot(index).getItem();
        return stack == null ? ItemStack.EMPTY : stack;
    }

    static String idPath(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    }

    static String displayName(ItemStack stack) {
        return stack.getHoverName().getString().replaceAll("§.", "").trim();
    }

    /**
     * Whether the item terminals ("Select all the …", "What starts with …") lay their 27 items out
     * with a pane border around them. A board of exactly 27 slots is all content - and there the
     * panes are items to click, not chrome, so they must not be filtered away.
     */
    private static boolean hasBorder(int upper) {
        return upper > 27;
    }

    /** Chrome rather than a clickable item: an empty slot, or a border pane on a bordered board. */
    static boolean isChrome(ItemStack stack, boolean bordered) {
        if (stack.isEmpty()) {
            return true;
        }
        return bordered && idPath(stack).contains("glass_pane");
    }

    /** The stained-glass colour of a pane ({@code lime_stained_glass_pane} → "lime"), else null. */
    static String paneColour(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        String path = idPath(stack);
        int index = path.indexOf("_stained_glass");
        return index > 0 ? path.substring(0, index) : null;
    }

    /**
     * The dye colour of any item: from its id ({@code light_blue_wool}) first, then from its display
     * name ("Light Blue Wool") for the items whose id does not carry it. Longest colour first in both
     * passes, so "light blue" never comes back as "blue".
     */
    static String colourOf(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        String path = idPath(stack);
        for (String colour : COLOURS) {
            if (path.startsWith(colour + "_")) {
                return colour;
            }
        }
        String name = displayName(stack).toLowerCase(Locale.ROOT);
        for (String colour : COLOURS) {
            if (name.contains(colour.replace('_', ' '))) {
                return colour;
            }
        }
        return null;
    }

    /** The colour word a "Select all the X items!" title names, or null. */
    static String colourInTitle(String title) {
        String norm = title.toLowerCase(Locale.ROOT);
        for (String colour : COLOURS) {
            if (norm.contains(colour.replace('_', ' ')) || norm.contains(colour)) {
                return colour;
            }
        }
        return null;
    }

    private static int indexInCycle(String colour) {
        if (colour != null) {
            for (int i = 0; i < CYCLE.length; i++) {
                if (CYCLE[i].equals(colour)) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** The clickable button (or lever) in a melody row, or -1 when the row has none. */
    private static int buttonInRow(AbstractContainerMenu menu, int row, int columns, int upper) {
        int start = row * columns;
        for (int i = start; i < Math.min(start + columns, upper); i++) {
            String path = idPath(item(menu, i));
            if (path.endsWith("_button") || path.equals("lever")) {
                return i;
            }
        }
        return -1;
    }
}
