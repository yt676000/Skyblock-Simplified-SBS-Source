/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.croesus.logic;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.economy.prices.ItemPriceKey;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One reading of the open Croesus menu: which slots are runs, and which of them still owe you a
 * chest.
 *
 * <p><b>Throttled the same way the chest ranking is</b>, and for the same reason: the caller is a
 * render hook, and stripping the lore of every slot in a paged menu once a frame is the hot-path
 * mistake this repository has already written down twice. The menu's state id is part of the key, so
 * turning a page re-reads at once rather than waiting out the interval.
 *
 * <p><b>It also writes down what it found</b>, in two places that answer two different questions.
 * {@link CroesusStore} keeps the count for the reminder, so leaving the menu does not lose it. The
 * {@code [SBS][Croesus]} log line reports how many items were walked past against how many were
 * recognised - which is the one number that says whether the configured phrases are right, and it
 * is written whether or not anything matched, because a feature whose only diagnostic appears when
 * it works cannot report that it does not.
 */
public final class CroesusScan {

    /** Long enough that a render hook cannot re-read a menu per frame, short enough to feel live. */
    private static final long REFRESH_MS = 250L;

    /**
     * Throttle for the summary line.
     *
     * <p>The scan itself runs four times a second while the menu is open; the line it writes is a
     * diagnostic, and a diagnostic that writes four lines a second is one nobody can read the rest of
     * the log around.
     */
    private static final long LOG_INTERVAL_MS = 5_000L;

    /** How many items the debug dump writes, and how many lore lines of each. Bounded on purpose. */
    private static final int DUMP_ITEMS = 8;
    private static final int DUMP_LINES = 10;

    private static Result cached = Result.NONE;
    private static long cachedAt;
    private static int cachedStateId = Integer.MIN_VALUE;
    private static AbstractContainerMenu cachedMenu;

    /** The menu the lore dump has already been written for, so it runs once per opening. */
    private static AbstractContainerMenu dumpedMenu;

    private static long lastLogAt;

    private CroesusScan() {
    }

    /**
     * What the menu's entries are.
     *
     * @param entries  slot index to its state, for the slots that were recognised at all
     * @param counts   slot index to the number of chests the entry named, when it named one
     * @param unopened how many entries still owe a chest
     * @param done     how many entries are finished
     * @param items    how many non-empty slots were walked past, recognised or not
     */
    public record Result(Map<Integer, CroesusMenu.RunState> entries, Map<Integer, Integer> counts,
                         int unopened, int done, int items) {

        public static final Result NONE = new Result(Map.of(), Map.of(), 0, 0, 0);

        /** Whether there is anything to draw. */
        public boolean isEmpty() {
            return entries.isEmpty();
        }

        /**
         * One line for the settings page: what the last look at Croesus actually recognised.
         *
         * <p>"12 items, 0 recognised" is the whole "are the phrases right" question answered in
         * game, which is what keeps a wrong default from looking like a broken feature.
         */
        public String describe() {
            if (items == 0) {
                return "Croesus has not been open yet this session";
            }
            return items + " item(s), " + unopened + " with chests left, " + done + " finished, "
                    + (items - unopened - done) + " not recognised";
        }
    }

    /** The last scan's findings, for the settings page. Never {@code null}. */
    public static Result last() {
        return cached;
    }

    /**
     * Reads the open menu, or returns the cached reading.
     *
     * @param screen the open container screen
     * @param upper  slots belonging to the menu itself; the player's own inventory is excluded by
     *               the caller, since a run entry can never be in there
     */
    public static Result get(AbstractContainerScreen<?> screen, int upper) {
        AbstractContainerMenu menu = screen.getMenu();
        long now = System.currentTimeMillis();
        int stateId = menu.getStateId();
        if (menu == cachedMenu && stateId == cachedStateId && now - cachedAt < REFRESH_MS) {
            return cached;
        }
        cachedMenu = menu;
        cachedStateId = stateId;
        cachedAt = now;
        cached = scan(menu, upper);
        return cached;
    }

    private static Result scan(AbstractContainerMenu menu, int upper) {
        SBSConfig.DungeonsSettings cfg = ConfigManager.getInstance().get().dungeons;
        Map<Integer, CroesusMenu.RunState> entries = new HashMap<>();
        Map<Integer, Integer> counts = new HashMap<>();
        int unopened = 0;
        int done = 0;
        int items = 0;
        StringBuilder dump = cfg.croesusDebugLog && menu != dumpedMenu ? new StringBuilder() : null;

        for (int i = 0; i < upper && i < menu.getItems().size(); i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            items++;
            List<String> lore = ItemPriceKey.lore(stack);
            if (dump != null && items <= DUMP_ITEMS) {
                appendDump(dump, i, stack, lore);
            }
            CroesusMenu.RunState state =
                    CroesusMenu.stateOf(lore, cfg.croesusOpened, cfg.croesusUnopened);
            if (state == CroesusMenu.RunState.UNKNOWN) {
                continue;
            }
            entries.put(i, state);
            if (state == CroesusMenu.RunState.UNOPENED) {
                unopened++;
                int count = CroesusMenu.countIn(lore, cfg.croesusUnopened);
                if (count > 0) {
                    counts.put(i, count);
                }
            } else {
                done++;
            }
        }

        if (dump != null) {
            dumpedMenu = menu;
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Croesus] menu lines (first {} items):{}",
                    DUMP_ITEMS, dump);
        }
        long now = System.currentTimeMillis();
        if (now - lastLogAt >= LOG_INTERVAL_MS) {
            lastLogAt = now;
            SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Croesus] items={} chestsLeft={} finished={} unrecognised={}",
                    items, unopened, done, items - unopened - done);
        }
        // Remembered here rather than by the reminder, because this is the only moment the real
        // number is on screen: the reminder runs when the menu is long shut.
        CroesusStore.getInstance().record(unopened);
        return new Result(Map.copyOf(entries), Map.copyOf(counts), unopened, done, items);
    }

    /** One item's name and lore, for the dump. Bounded in both directions. */
    private static void appendDump(StringBuilder out, int slot, ItemStack stack,
                                   List<String> lore) {
        out.append("\n  [").append(slot).append("] ")
                .append(sbs.modid.client.core.util.PlainText.strip(
                        stack.getHoverName().getString()));
        for (int i = 0; i < lore.size() && i < DUMP_LINES; i++) {
            out.append("\n        | ").append(lore.get(i));
        }
        if (lore.size() > DUMP_LINES) {
            out.append("\n        | ... ").append(lore.size() - DUMP_LINES).append(" more line(s)");
        }
    }
}
