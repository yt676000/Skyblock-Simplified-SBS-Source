/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.bingo.logic;

import sbs.modid.client.helper.bingo.model.BingoCard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns the Bingo Card menu's slots into a {@link BingoCard}. Pure - slots come in as plain name +
 * lore strings, colour-stripped - so it is tested without a game.
 *
 * <p><b>Every shape below is {@code ESTIMATED}.</b> Nobody on this project has opened the menu on a
 * Bingo profile yet; these are the expected shapes, kept together here so the first probe capture
 * (see {@code docs/features/bingo-card-overlay.md}, "Probe checklist") corrects them in one place.
 */
public final class BingoCardParser {

    /** A slot as the reader hands it over: its index, display name and lore, colour-stripped. */
    public record SlotView(int slot, String itemId, String name, List<String> lore) {
    }

    /** ESTIMATED: the menu title contains this (lower-case, as {@code MenuFrame} normalises it). */
    public static final String TITLE_KEY = "bingo card";

    /** ESTIMATED: 25 goals, columns 2-6 of rows 0-4 of a 9-wide chest. */
    public static final int GRID_COLUMN = 2;
    public static final int GRID_SIZE = 5;

    /** ESTIMATED: a finished goal says so in its lore. */
    private static final Pattern DONE = Pattern.compile("(?i)\\bGOAL REACHED\\b|^\\s*Completed!?\\s*$|\\bComplete!");
    /** ESTIMATED: a community goal is labelled as one. */
    private static final Pattern COMMUNITY = Pattern.compile("(?i)\\bCommunity Goal\\b");
    /** ESTIMATED: a community goal's progress line mentions its tier or a percentage. */
    private static final Pattern PROGRESS = Pattern.compile("(?i)\\bTier\\b.*|\\d+(?:\\.\\d+)?%");

    private BingoCardParser() {
    }

    /** Whether a slot index lies on the goal grid. */
    public static boolean onGrid(int slot) {
        int row = slot / 9;
        int column = slot % 9;
        return row < GRID_SIZE && column >= GRID_COLUMN && column < GRID_COLUMN + GRID_SIZE;
    }

    /**
     * The goals on the grid, in reading order. Empty slots and glass panes are skipped, so a card
     * with fewer goals than squares (or a wrong grid guess landing on filler) yields fewer goals
     * rather than invented ones.
     */
    public static List<BingoCard.Goal> parse(List<SlotView> slots) {
        List<BingoCard.Goal> goals = new ArrayList<>();
        for (SlotView view : slots) {
            if (!onGrid(view.slot()) || filler(view)) {
                continue;
            }
            boolean done = false;
            boolean community = false;
            String progress = null;
            for (String line : view.lore()) {
                if (DONE.matcher(line).find()) {
                    done = true;
                }
                if (COMMUNITY.matcher(line).find()) {
                    community = true;
                }
                if (progress == null && PROGRESS.matcher(line).find()) {
                    progress = line.trim();
                }
            }
            goals.add(new BingoCard.Goal(view.name().trim(), done, community, community ? progress : null));
        }
        return goals;
    }

    private static boolean filler(SlotView view) {
        String name = view.name() == null ? "" : view.name().trim();
        String id = view.itemId() == null ? "" : view.itemId().toLowerCase(Locale.ROOT);
        return name.isEmpty() || id.isEmpty() || id.equals("minecraft:air") || id.endsWith("glass_pane");
    }
}
