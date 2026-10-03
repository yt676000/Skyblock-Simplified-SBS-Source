/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.wardrobe;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads one page of Hypixel's Armor Sets menu from plain slot text - no {@code ItemStack}, so it
 * runs in a unit test against a real capture.
 *
 * <p>Layout (CONFIRMED, Layout Recorder 2026-10-02, docs/skyblock-ui/menus.md "Armor Sets"): title
 * {@code (1/3) Armor Sets}; slots 0-8 helmets, 9-17 chestplates, 18-26 leggings, 27-35 boots, one
 * column per set; 36-44 one dye per set named {@code Slot N: Ready} / {@code Slot N: Equipped},
 * where N is the GLOBAL set number. 48 Go Back, 49 Close, 53 Next Page.
 */
public final class ArmorSetsPage {

    /** Sets per page: one column each. */
    public static final int COLUMNS = 9;
    /** First slot of the dye row. */
    public static final int DYE_ROW = 36;

    private static final Pattern TITLE = Pattern.compile("^\\(([0-9]+)/([0-9]+)\\)\\s*armor sets$");
    private static final Pattern SLOT = Pattern.compile("^slot\\s+([0-9]+)\\s*:?\\s*(.*)$");

    /** What a set's dye says about it. EMPTY and LOCKED wordings are UNVERIFIED (never captured). */
    public enum Status { READY, EQUIPPED, EMPTY, LOCKED, UNKNOWN }

    /** One slot as text: item id (either {@code minecraft:gray_dye} or a SkyBlock id), name, lore. */
    public record SlotText(String id, String name, List<String> lore) {
        public SlotText {
            id = id == null ? "" : id;
            name = name == null ? "" : name;
            lore = lore == null ? List.of() : List.copyOf(lore);
        }
    }

    /** One column of the page: its global set number, status and the menu slot of its dye. */
    public record Column(int column, int setNumber, Status status, int dyeSlot) {
        /** Menu slot of piece {@code p} (0 helmet .. 3 boots) of this column. */
        public int pieceSlot(int p) {
            return p * COLUMNS + column;
        }
    }

    private ArmorSetsPage() {
    }

    /** {@code {page, pages}} from a colour-stripped title, or null when it is not Armor Sets. */
    public static int[] parseTitle(String title) {
        if (title == null) {
            return null;
        }
        Matcher m = TITLE.matcher(title.trim().toLowerCase(Locale.ROOT));
        if (!m.matches()) {
            return null;
        }
        int page = Integer.parseInt(m.group(1));
        int pages = Integer.parseInt(m.group(2));
        return page >= 1 && pages >= page ? new int[] {page, pages} : null;
    }

    public static boolean isArmorSets(String title) {
        return parseTitle(title) != null;
    }

    /**
     * The columns whose dye is readable. A dye still loading (empty slot) or one without a
     * {@code Slot N} name is skipped rather than guessed - the next frame reads it again.
     *
     * @param slots the container's slots by index; shorter lists simply yield fewer columns
     */
    public static List<Column> columns(List<SlotText> slots) {
        List<Column> out = new ArrayList<>();
        for (int c = 0; c < COLUMNS; c++) {
            int dye = DYE_ROW + c;
            if (dye >= slots.size() || slots.get(dye) == null) {
                continue;
            }
            SlotText text = slots.get(dye);
            Matcher m = SLOT.matcher(text.name().trim().toLowerCase(Locale.ROOT));
            if (!m.matches()) {
                continue;
            }
            out.add(new Column(c, Integer.parseInt(m.group(1)), status(text, m.group(2)), dye));
        }
        return out;
    }

    /** The status from the word after {@code Slot N:}, then the lore, then the dye colour. */
    static Status status(SlotText dye, String word) {
        String w = word.trim();
        if (w.startsWith("equipped")) {
            return Status.EQUIPPED;
        }
        if (w.startsWith("ready")) {
            return Status.READY;
        }
        if (w.startsWith("empty")) {
            return Status.EMPTY;
        }
        if (w.startsWith("locked")) {
            return Status.LOCKED;
        }
        String lore = String.join(" ", dye.lore()).toLowerCase(Locale.ROOT);
        if (lore.contains("click to unequip") || lore.contains("your current set")) {
            return Status.EQUIPPED;
        }
        if (lore.contains("click to equip")) {
            return Status.READY;
        }
        return dye.id().endsWith("lime_dye") ? Status.EQUIPPED : Status.UNKNOWN;
    }

    /** The page a global set number is on. */
    public static int pageOf(int setNumber) {
        return (setNumber - 1) / COLUMNS + 1;
    }

    /** Whether clicking this set's dye is a real action (equip or unequip). */
    public static boolean clickable(Status status) {
        return status == Status.READY || status == Status.EQUIPPED;
    }
}
