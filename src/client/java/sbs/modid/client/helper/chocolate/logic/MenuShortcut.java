/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.chocolate.logic;

import sbs.modid.client.helper.chocolate.model.ChocolateSnapshot;
import sbs.modid.client.helper.chocolate.model.FactoryUpgrade;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.DoubleFunction;

/**
 * The rules behind the Chocolate Factory button in Hypixel's {@code SkyBlock Menu}, on plain values
 * so they can be tested without a game: where the button may sit, when it is drawn, what its
 * tooltip says and how often a click may send the command.
 *
 * <p>Layout: {@code docs/skyblock-ui/menus.md}, <i>SkyBlock Menu</i>. Hypixel's own buttons are in
 * {@link #HYPIXEL_BUTTONS}; every other slot of the 54 is a filler pane in both captures. The button
 * only ever replaces a pane, never a real item - see {@link #isFiller}.
 */
public final class MenuShortcut {

    /** The menu's title, colour-stripped, trimmed and lowercased as {@code MenuFrame} gives it. */
    public static final String TITLE = "skyblock menu";

    /**
     * The command a click sends, without the slash. UNVERIFIED: the factory is expected to answer to
     * both this and a short alias, and the long form is used until one of them is seen working. The
     * click logs the first menu that opens after it, which is how this gets confirmed.
     */
    public static final String COMMAND = "chocolatefactory";

    /** Right of Personal Bank (33), beside the other player-facing buttons. A filler pane there. */
    public static final int DEFAULT_SLOT = 34;

    /** Slots in the 54-slot menu. */
    public static final int MENU_SLOTS = 54;

    /** Slots Hypixel puts a real button in. CONFIRMED by the 2026-09-28 and 2026-10-04 captures. */
    public static final Set<Integer> HYPIXEL_BUTTONS = Set.of(
            13, 19, 20, 21, 22, 23, 24, 25, 29, 30, 31, 32, 33, 47, 48, 49, 50, 51);

    /** Minimum time between two commands, in milliseconds. */
    public static final long COOLDOWN_MS = 1_000L;

    private MenuShortcut() {
    }

    /** Every slot the player may put the button in: the menu's filler slots, ascending. */
    public static List<Integer> freeSlots() {
        List<Integer> slots = new ArrayList<>(MENU_SLOTS - HYPIXEL_BUTTONS.size());
        for (int slot = 0; slot < MENU_SLOTS; slot++) {
            if (!HYPIXEL_BUTTONS.contains(slot)) {
                slots.add(slot);
            }
        }
        return slots;
    }

    /**
     * The configured slot if it is one the button may use, else {@link #DEFAULT_SLOT}. A hand-edited
     * or shared config can carry any number; one that points at a Hypixel button would otherwise
     * hide the button for good, with nothing on the settings page to say why.
     */
    public static int resolveSlot(int configured) {
        return configured >= 0 && configured < MENU_SLOTS && !HYPIXEL_BUTTONS.contains(configured)
                ? configured : DEFAULT_SLOT;
    }

    /** How a slot is named in the picker: its number and its place in the grid, both from 1. */
    public static String slotLabel(int slot) {
        return "Slot " + slot + " (row " + (slot / 9 + 1) + ", column " + (slot % 9 + 1) + ")";
    }

    /** The inverse of {@link #slotLabel}, or {@code -1} for text it did not produce. */
    public static int slotFromLabel(String label) {
        if (label == null || !label.startsWith("Slot ")) {
            return -1;
        }
        int end = label.indexOf(' ', 5);
        try {
            return Integer.parseInt(end < 0 ? label.substring(5) : label.substring(5, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Whether a slot holds one of Hypixel's filler panes: a glass pane with a blank name and no lore.
     * The name and lore are the veto - a pane that says something is a control, and the button is
     * never drawn over a control.
     *
     * @param itemPath  the item's registry path, e.g. {@code black_stained_glass_pane}
     * @param plainName the hover name with colour codes stripped
     * @param loreLines how many lore lines the item carries
     */
    public static boolean isFiller(String itemPath, String plainName, int loreLines) {
        return itemPath != null && itemPath.endsWith("glass_pane")
                && (plainName == null || plainName.isBlank())
                && loreLines == 0;
    }

    /**
     * Whether the button is drawn and takes clicks.
     *
     * @param enabled         the setting
     * @param normalisedTitle the open menu's title as {@code MenuFrame.normalised()} gives it
     * @param slotIsFiller    whether the target slot holds a filler pane right now
     */
    public static boolean shows(boolean enabled, String normalisedTitle, boolean slotIsFiller) {
        return enabled && TITLE.equals(normalisedTitle) && slotIsFiller;
    }

    /**
     * The button's tooltip, colour codes included. Numbers come from the last factory reading and
     * that reading's age is always the line after them, because none of it is live.
     *
     * @param snapshot  the profile's last capture; never null
     * @param helperOn  whether the factory reader is switched on - without it there is never a
     *                  reading, and the hint has to say so rather than promise one
     * @param now       {@code System.currentTimeMillis()}
     * @param format    how a number is written ({@code NumberDisplay::format} in game)
     */
    public static List<String> tooltip(ChocolateSnapshot snapshot, boolean helperOn, long now,
                                       DoubleFunction<String> format) {
        List<String> lines = new ArrayList<>(8);
        lines.add("§6Chocolate Factory");
        if (snapshot == null || snapshot.empty()) {
            lines.add(helperOn
                    ? "§7Open it once to see your numbers here"
                    : "§7Switch on the Chocolate Factory helper to see your numbers here");
        } else {
            if (snapshot.balance >= 0) {
                lines.add("§7Chocolate: §f" + format.apply(snapshot.balance));
            }
            if (snapshot.perSecond > 0) {
                lines.add("§7Per second: §f" + format.apply(snapshot.perSecond));
            }
            FactoryUpgrade best = UpgradeRanking.best(snapshot.upgrades);
            if (best != null) {
                lines.add("§7Best next upgrade: §f" + best.name());
            }
            lines.add("§8Read " + age(snapshot.capturedAt, now));
        }
        lines.add("");
        lines.add("§eClick to open");
        return lines;
    }

    /** How long ago {@code capturedAt} was, the way the factory page and the tooltip both say it. */
    public static String age(long capturedAt, long now) {
        long minutes = Math.max(0, now - capturedAt) / 60_000L;
        return minutes < 1 ? "just now"
                : minutes < 60 ? minutes + " min ago" : (minutes / 60) + " h ago";
    }

    /**
     * One command per click, and never two within {@link #COOLDOWN_MS}. A double click, a held
     * button or a lagging menu that is clicked again must still send a single command.
     */
    public static final class Cooldown {

        private long lastFiredAt = Long.MIN_VALUE;

        /** Whether a command may be sent at {@code now}; records the send when it may. */
        public boolean tryFire(long now) {
            if (lastFiredAt != Long.MIN_VALUE && now - lastFiredAt < COOLDOWN_MS) {
                return false;
            }
            lastFiredAt = now;
            return true;
        }
    }
}
