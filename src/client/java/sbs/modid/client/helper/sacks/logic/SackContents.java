/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.sacks.logic;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.core.util.MathEval;
import sbs.modid.client.core.util.PlainText;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads an open sack menu into rows: what is in it, and <b>how much</b> of each.
 *
 * <h2>The one thing that makes this class necessary</h2>
 * A sack shows one icon per item type. The number on that icon is not the amount you are holding -
 * it is the stack size of a display item, which is 1. The real figure is written into the item's
 * lore, so a sack cannot be counted the way every other container in the game can be, and anything
 * that tries ({@code helper/storage/StorageIndex} does, today) under-reports a sack holding three
 * hundred thousand cobblestone as holding one.
 *
 * <p>This is <b>the</b> parser for that number. {@code StorageIndex} is meant to read through it too
 * once the format below is confirmed - one parser, two readers - rather than growing a second copy
 * that disagrees with this one about an edge.
 *
 * <h2>⚠ The format is UNVERIFIED</h2>
 * No real sack menu has been captured yet. The expected shape is a lore line reading
 * {@code Stored: 1,234/20.1k}, and that is what {@link #STORED} matches; {@link #ANY_FRACTION} is a
 * looser fallback for any {@code <amount>/<capacity>} line, in case the label is worded differently
 * or translated. <b>Until a probe confirms it, every amount this returns is a guess.</b> Every row
 * whose amount could not be read is logged once per menu under {@code [SBS][Sacks]} with its lore,
 * so the real lines can be read straight out of {@code latest.log} and pasted in here with a date,
 * the way the other verified parsers in this repo carry theirs.
 *
 * <p>Capture with {@code /sbs probe} on a normal sack (Mining or Agronomy), the Gemstones sack, the
 * Sack of Sacks, and a paging sack if one exists. See {@code docs/features/sack-overlay.md}.
 */
public final class SackContents {

    /** Returned by {@link #storedAmount} when the lore does not say - distinct from "0 stored". */
    public static final long UNKNOWN = -1L;

    /**
     * The expected line: {@code Stored: 1,234/20.1k}. Only the first figure is the amount; the
     * second is the sack's capacity for that item and is deliberately not captured - a full sack
     * and an empty one differ in the first number alone.
     */
    private static final Pattern STORED =
            Pattern.compile("(?i)\\bstored\\s*:?\\s*([0-9][0-9,.]*\\s*[kmb]?)");

    /**
     * Fallback for a differently worded (or translated) line: any {@code amount/capacity} pair.
     * Deliberately second - a sack's lore can carry other fractions ("Tier 2/5"), and the labelled
     * line is the one that is certainly right when it is there.
     */
    private static final Pattern ANY_FRACTION =
            Pattern.compile("([0-9][0-9,.]*\\s*[kmb]?)\\s*/\\s*[0-9][0-9,.]*\\s*[kmb]?");

    /** One item in the open sack. {@code stored} may be {@link #UNKNOWN}. */
    public record Row(ItemStack icon, String id, String name, long stored) {

        /** Whether this row's amount is a figure rather than a shrug. */
        public boolean known() {
            return stored != UNKNOWN;
        }
    }

    private SackContents() {
    }

    /**
     * How many of this item the sack is holding, or {@link #UNKNOWN} when its lore does not say.
     *
     * <p>{@code UNKNOWN} and {@code 0} are different answers and callers must keep them apart: zero
     * stored is a real, hideable row, while unknown means this parser did not understand the menu
     * and the honest thing to show is a dash, never a zero.
     */
    public static long storedAmount(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return UNKNOWN;
        }
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return UNKNOWN;
        }
        Long loose = null;
        for (Component line : lore.lines()) {
            String text = PlainText.strip(line.getString());
            Matcher labelled = STORED.matcher(text);
            if (labelled.find()) {
                Long value = number(labelled.group(1));
                if (value != null) {
                    return value;
                }
            }
            if (loose == null) {
                Matcher fraction = ANY_FRACTION.matcher(text);
                if (fraction.find()) {
                    loose = number(fraction.group(1));
                }
            }
        }
        return loose == null ? UNKNOWN : loose;
    }

    /**
     * One figure out of the lore: {@code "1,234"} and {@code "20.1k"} are both numbers here.
     *
     * <p>The suffix table comes from {@link MathEval} rather than a second copy of it - the commas
     * are stripped first because they are thousands separators in lore and nothing at all to an
     * expression parser. The token is matched by the patterns above, so it can only ever be digits,
     * separators and a scale letter; there is no operator in it for the evaluator to find.
     */
    static Long number(String token) {
        if (token == null) {
            return null;
        }
        String cleaned = token.replace(",", "").replace(" ", "").trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        Double value = MathEval.eval(cleaned);
        if (value == null || value.isNaN() || value.isInfinite() || value < 0) {
            return null;
        }
        return Math.round(value);
    }

    /**
     * Every item in the open sack, in menu order. Player-inventory slots are skipped: those are
     * your own backpack, not the sack's contents, and they count normally anyway.
     *
     * <p>Rows whose amount could not be read are still returned, as {@link #UNKNOWN} - dropping them
     * would hide exactly the items a broken parser is failing on, which is the last thing that
     * should be invisible.
     */
    public static List<Row> read(AbstractContainerScreen<?> screen) {
        List<Row> rows = new ArrayList<>();
        if (screen == null) {
            return rows;
        }
        List<String> unparsed = new ArrayList<>();
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container instanceof Inventory) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String name = PlainText.strip(stack.getHoverName().getString()).trim();
            if (isChrome(stack, name)) {
                continue;
            }
            long stored = storedAmount(stack);
            String id = SkyblockItem.id(stack);
            rows.add(new Row(stack, id == null ? "" : id.toUpperCase(Locale.ROOT), name, stored));
            if (stored == UNKNOWN && unparsed.size() < 4) {
                unparsed.add(name + " -> " + firstLore(stack));
            }
        }
        if (!unparsed.isEmpty()) {
            logUnparsed(screen, rows.size(), unparsed);
        }
        return rows;
    }

    /**
     * Hypixel's menu furniture: the filler panes, the back arrow, the page markers. Named by what
     * they are rather than by slot index, because the index moves with the menu and the material
     * does not.
     */
    private static boolean isChrome(ItemStack stack, String name) {
        if (name.isEmpty()) {
            return true;
        }
        String path = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(stack.getItem()).getPath();
        return path.endsWith("stained_glass_pane") || path.equals("barrier") || path.equals("arrow");
    }

    /** The item's lore as one line, for the diagnostic - the whole point is to see the real text. */
    private static String firstLore(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null || lore.lines().isEmpty()) {
            return "(no lore)";
        }
        StringBuilder out = new StringBuilder();
        for (Component line : lore.lines()) {
            if (out.length() > 0) {
                out.append(" | ");
            }
            out.append(PlainText.strip(line.getString()).trim());
        }
        return out.toString();
    }

    /** Throttled by menu: one report per opened sack, not one per scan. */
    private static int lastLoggedContainer = -1;

    private static void logUnparsed(AbstractContainerScreen<?> screen, int rowCount,
                                    List<String> samples) {
        int containerId = screen.getMenu().containerId;
        if (containerId == lastLoggedContainer) {
            return;
        }
        lastLoggedContainer = containerId;
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Sacks] {} row(s), amount unreadable on some. The stored-amount format is "
                        + "unverified - paste these lines into SackContents. Samples: {}",
                rowCount, String.join("  ;;  ", samples));
    }
}
