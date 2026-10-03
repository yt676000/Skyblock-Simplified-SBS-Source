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
import sbs.modid.client.core.config.ConfigManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ultrasequencer helper.
 *
 * <p><b>Board encoding (verified from a live board dump, 2026-07-23).</b> Every sequence tile
 * carries its own number twice: as its <b>display name</b> ("1", "2", "3", ...) and as its
 * <b>stack size</b> (bone meal x1 named "1", light blue dye x2 named "2", lapis x3 named "3", ...).
 * Everything else on the board – the glowstone "Remember the pattern!" instruction, the timer
 * clock, the "Go Back" arrow – has a non-numeric name and stack size 1. So the tile test is simply
 * <i>numeric name (or stacked above 1)</i>, and the capture needs no reveal-order guessing at all:
 * whenever a numbered tile is visible, remember {@code slot -> number}; the answer is the numbers
 * in ascending order. (The old arrival-order diff captured the instruction item as a tile, which
 * poisoned the click list and vetoed the player's correct clicks into a passive round.)
 *
 * <p><b>Round boundaries.</b> The board is dealt fresh every round – the same number can move to a
 * different slot. There is no reliable "(Round X)" in the title, so a new deal is detected by
 * <b>contradiction</b>: a slot showing a different number than remembered, or a number appearing on
 * a different slot than remembered, means the memory is from the previous round and is dropped.
 * Completing the click-back (nothing left unclicked) also resets, so ghosts never bleed into the
 * next round's show.
 *
 * <p><b>Click policing.</b> Only the lowest unclicked number is allowed through, with a valve:
 * insisting on the same vetoed slot twice turns the round passive, so a desynced capture can never
 * lock the player out of finishing by hand.
 */
final class UltrasequencerSolver {

    private static final Pattern ROUND = Pattern.compile("\\((?:Round\\s*)?([0-9]+)");

    /** Light green for the tile to click next; dark green for the rest of the sequence. */
    private static final int NEXT_GREEN = 0xFF55FF55;
    private static final int REST_GREEN = 0xFF1E7D1E;

    /** Slot -> the tile's number (from its name, or its stack size), as far as this round showed. */
    private final Map<Integer, Integer> numbers = new HashMap<>();
    /** Slot -> a copy of the tile, so it can be ghosted back while the board covers it. */
    private final Map<Integer, ItemStack> tiles = new HashMap<>();
    /** Slots already pressed this round. */
    private final Set<Integer> clicked = new HashSet<>();

    private int round;
    /** True when the capture was overruled by the player: render nothing, veto nothing. */
    private boolean passive;

    /** The slot a veto last hit, and how often in a row - the guard's stand-down valve. */
    /** Shared with Chronomatron so both games give up the same way - see {@link MisclickValve}. */
    private final MisclickValve valve = new MisclickValve();

    private long lastDumpAt;

    void reset() {
        round = 0;
        newRound();
    }

    /** Drops everything: the next deal is a brand new board, last round's slots mean nothing. */
    private void newRound() {
        numbers.clear();
        tiles.clear();
        clicked.clear();
        passive = false;
        valve.reset();
    }

    void scan(AbstractContainerMenu menu, int upper, String title) {
        int parsed = parseRound(title);
        if (parsed > 0 && parsed != round) {
            round = parsed;
            newRound();
        }

        for (int i = 0; i < upper; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            Integer number = numberOf(stack);
            if (number == null) {
                continue;
            }
            // A contradiction with the remembered board = this is a NEW deal: drop the old round's
            // memory (the redeal moves numbers to new slots) and start capturing from this tile.
            Integer atSlot = numbers.get(i);
            Integer knownSlot = slotOf(number);
            if ((atSlot != null && atSlot != number) || (knownSlot != null && knownSlot != i)) {
                newRound();
            }
            numbers.put(i, number);
            tiles.put(i, stack.copyWithCount(1));
        }

        // Round clicked back completely: reset now, so nothing ghosts over the next round's show.
        if (!numbers.isEmpty() && nextSlot() < 0) {
            newRound();
        }
        dumpBoard(menu, upper);
    }

    /**
     * The tile's number, or {@code null} when the item is not a sequence tile. Primary: the numeric
     * display name every tile carries; fallback: a stack size above 1. Instruction items (glowstone
     * "Remember the pattern!", the timer clock, the "Go Back" arrow) are all non-numeric singles, so
     * this one gate keeps every one of them out of the sequence.
     */
    private static Integer numberOf(ItemStack stack) {
        if (ExperimentationTable.isFiller(stack)) {
            return null;
        }
        String name = strip(stack.getHoverName().getString());
        if (name.matches("[0-9]+")) {
            return Integer.parseInt(name);
        }
        return stack.getCount() > 1 ? stack.getCount() : null;
    }

    /** The slot the given number was remembered at, or {@code null} when not seen this round. */
    private Integer slotOf(int number) {
        for (Map.Entry<Integer, Integer> entry : numbers.entrySet()) {
            if (entry.getValue() == number) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** The remembered slots in click order (ascending number). */
    private List<Integer> clickOrder() {
        List<Integer> sorted = new ArrayList<>(numbers.keySet());
        sorted.sort(Comparator.comparingInt(numbers::get));
        return sorted;
    }

    /** The slot that must be clicked next, or -1 when nothing is left (or nothing was captured). */
    private int nextSlot() {
        for (int slot : clickOrder()) {
            if (!clicked.contains(slot)) {
                return slot;
            }
        }
        return -1;
    }

    void render(GuiGraphicsExtractor g, Font font, AbstractContainerMenu menu, int left, int top) {
        if (passive || numbers.isEmpty()) {
            return;
        }
        boolean gradient = ConfigManager.getInstance().get().experimentation.ultrasequencerOrderGradient;
        int nextSlot = nextSlot();
        int total = numbers.size();
        // Each tile keeps Hypixel's OWN number for the whole round - clicking "1" never re-labels
        // "2". Clicked tiles drop out; only the next tile is lit bright.
        for (int slot : clickOrder()) {
            if (clicked.contains(slot) || slot < 0 || slot >= menu.slots.size()) {
                continue;
            }
            int number = numbers.get(slot);
            Slot s = menu.getSlot(slot);
            int x = left + s.x;
            int y = top + s.y;
            // Ghost the tile back while the board covers it (the input phase hides the numbers).
            if (ExperimentationTable.isFiller(s.getItem()) && tiles.containsKey(slot)) {
                g.item(tiles.get(slot), x, y);
            }
            boolean next = slot == nextSlot;
            int color = next ? NEXT_GREEN
                    : gradient ? gradientGreen(number - 1, total) : REST_GREEN;
            ExperimentationTable.outline(g, x, y, color);
            g.text(font, Component.literal(String.valueOf(number)), x + 1, y + 1,
                    next ? 0xFFFFFFFF : 0xFFBBBBBB);
        }
    }

    /** A green whose brightness fades from the front of the sequence to the back (gradient mode). */
    private static int gradientGreen(int position, int total) {
        float t = total <= 1 ? 0f : Math.min(1f, (float) position / (total - 1));   // 0 front, 1 back
        int g = Math.round(0xFF - t * (0xFF - 0x33));   // 255 -> 51
        return 0xFF000000 | (g << 8);
    }

    /**
     * Only the next tile in line may be clicked. Nothing captured = no policing, and two refusals in
     * a row turn the round passive rather than trapping the player.
     *
     * <p>That used to be two refusals <b>of the same slot</b>, which almost never happened: a player
     * whose click is refused concludes they misread the board and tries a different tile, resetting
     * the count every time. The way out existed and could not be reached - see {@link MisclickValve}.
     */
    boolean allowsClick(int index) {
        int next = nextSlot();
        if (passive || valve.open() || next < 0) {
            return true;
        }
        if (index == next) {
            valve.allowed();
            return true;
        }
        if (valve.veto(index)) {
            passive = true;   // the capture is evidently wrong - stand down for this round
            return true;
        }
        return false;
    }

    void onClick(int index) {
        if (!passive && index == nextSlot()) {
            clicked.add(index);
            valve.allowed();
        }
    }

    private static String strip(String text) {
        return text == null ? "" : text.replaceAll("§.", "").trim();
    }

    /**
     * Throttled diagnostic (~every 1.5s): every non-filler board item with its type, count and name,
     * tiles marked. This is what verified the number encoding - read it in the instance log if the
     * helper still reads a round wrong.
     */
    private void dumpBoard(AbstractContainerMenu menu, int upper) {
        long now = System.currentTimeMillis();
        if (now - lastDumpAt < 1500) {
            return;
        }
        lastDumpAt = now;
        StringBuilder board = new StringBuilder();
        for (int i = 0; i < upper; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (ExperimentationTable.isFiller(stack)) {
                continue;
            }
            board.append(i).append(':')
                    .append(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath())
                    .append('x').append(stack.getCount())
                    .append('"').append(strip(stack.getHoverName().getString())).append('"')
                    .append(numberOf(stack) != null ? "*TILE" : "").append(' ');
        }
        sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Ultrasequencer] round={} numbers={} clicked={} next={} board=[{}]",
                round, numbers, clicked, nextSlot(), board.toString().trim());
    }

    String debug() {
        return "round=" + round + " numbers=" + numbers.size() + " clicked=" + clicked.size()
                + " next=" + nextSlot() + " passive=" + passive;
    }

    private static int parseRound(String title) {
        Matcher m = ROUND.matcher(title);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }
}
