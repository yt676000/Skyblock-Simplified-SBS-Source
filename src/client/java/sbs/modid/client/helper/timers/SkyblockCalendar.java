/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.timers;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.util.PlainText;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reads SkyBlock's own Calendar menu while the player has it open.
 *
 * <p><b>Why bother, given the clock already runs?</b> The cadence of Jacob's Farming Contest is
 * known — every third SkyBlock day — but its <i>phase</i> is not derivable from anything on screen,
 * so {@link EventTimers} otherwise has to wait for a contest to actually run before it can predict
 * the next one. That is up to a real hour of the row saying "learning...". The calendar is Hypixel's
 * own statement about which days the coming contests fall on, so one look at it settles the phase
 * outright.
 *
 * <p><b>Passive only.</b> Nothing here opens the menu, clicks in it or asks the server for it — the
 * mod does not act for the player. It reads what is on screen because the player put it there, and
 * if they never open the calendar the timer behaves exactly as it did before.
 *
 * <p><b>Unverified against a live client.</b> The wording and layout of the calendar are not
 * confirmed, so this is written to fail quietly rather than confidently: a contest entry with no
 * readable date is logged instead of guessed at, and days that disagree about the cycle are thrown
 * away rather than averaged. What it finds is checked against itself in {@link EventTimers} before
 * anything is stored.
 */
final class SkyblockCalendar {

    /** Hypixel's name for the contest, matched loosely because only the wording is uncertain. */
    private static final Pattern JACOB = Pattern.compile("(?i)jacob|farming contest");

    /** The bottom 36 slots of any menu are the player's own inventory, never the menu's content. */
    private static final int PLAYER_INVENTORY_SLOTS = 36;

    /**
     * What one look at the calendar found.
     *
     * @param contestDays     day-of-year (1-based, as {@link EventTimers} counts) of every contest
     *                        entry that carried a readable date
     * @param undatedExample  the text of one entry that named a contest but carried no date, for the
     *                        log — {@code null} when there was none. This is the shape that says
     *                        "the calendar words it differently than we assumed".
     */
    record Reading(List<Integer> contestDays, String undatedExample) {

        static final Reading NOTHING = new Reading(List.of(), null);

        boolean isEmpty() {
            return contestDays.isEmpty() && undatedExample == null;
        }
    }

    private SkyblockCalendar() {
    }

    /** Reads the open calendar, or {@link Reading#NOTHING} when no calendar is open. */
    static Reading read() {
        var screen = GuiStateManager.getInstance().getCurrentScreen();
        if (!(screen instanceof AbstractContainerScreen<?> container) || !isCalendar(screen.getTitle())) {
            return Reading.NOTHING;
        }
        var menu = container.getMenu();
        int content = Math.max(0, menu.getItems().size() - PLAYER_INVENTORY_SLOTS);
        List<Integer> days = new ArrayList<>();
        String undated = null;
        for (int slot = 0; slot < content; slot++) {
            ItemStack stack = menu.getSlot(slot).getItem();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String text = textOf(stack);
            if (!JACOB.matcher(text).find()) {
                continue;
            }
            // The date has to be on the same item as the contest name - that is what makes it that
            // contest's date rather than some other entry's.
            int day = EventTimers.dayOfYearIn(text);
            if (day > 0) {
                days.add(day);
            } else if (undated == null) {
                undated = text;
            }
        }
        return days.isEmpty() && undated == null ? Reading.NOTHING : new Reading(days, undated);
    }

    /**
     * Whether an open menu is the calendar. Deliberately loose: a false positive costs nothing (a
     * menu that is not the calendar has no dated contest entries to find), while a false negative
     * would mean never looking and therefore never even logging that the title has changed.
     */
    private static boolean isCalendar(Component title) {
        if (title == null) {
            return false;
        }
        String text = PlainText.strip(title.getString()).toLowerCase(Locale.ROOT);
        return text.contains("calendar") || text.contains("event");
    }

    /** An item's name and lore as one plain string, which is what both patterns are matched on. */
    private static String textOf(ItemStack stack) {
        StringBuilder out = new StringBuilder(PlainText.strip(stack.getHoverName().getString()));
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore != null) {
            for (Component line : lore.lines()) {
                out.append(' ').append(PlainText.strip(line.getString()));
            }
        }
        return out.toString();
    }
}
