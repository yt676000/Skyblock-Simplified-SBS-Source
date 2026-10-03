/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.garden.logic;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Garden visitor's menu: recognising one, and reading what the visitor wants. The one detector
 * for it - the refuse-guard ({@link VisitorGuard}) and the Bazaar buttons both ask here.
 *
 * <p><b>The wording is {@code ESTIMATED}, never captured</b> (no probe of a visitor menu exists as of
 * 2026-09-24). Expected: an {@code Accept Offer} item and a {@code Refuse Offer} item in the same
 * menu, and on the accept item a lore block like
 * <pre>
 * Items Required:
 *  Enchanted Hay Bale x2
 *  Wheat x1,280
 *
 * Rewards:
 * </pre>
 * The block ends at a blank line or the next heading. A line without {@code x<N>} is one item. The
 * callers log what they read under {@code [SBS][Visitor]}, so one visit shows whether this is right.
 */
public final class VisitorMenu {

    /** One item a visitor wants. */
    public record Required(String name, int amount) {
    }

    private static final String REQUIRED_HEADER = "items required:";
    /** " Enchanted Hay Bale x2" - the name, then an optional "x" amount that may carry commas. */
    private static final Pattern ITEM_LINE = Pattern.compile("^(.*?[A-Za-z].*?)(?:\\s+x(\\d[\\d,]*))?$");

    private VisitorMenu() {
    }

    // ------------------------------------------------------------------ buttons

    /** The visitor's refuse/decline button (name-based). */
    public static boolean isRefuseButton(ItemStack stack) {
        String name = nameOf(stack);
        return name.contains("refuse") || name.contains("decline");
    }

    /** The visitor's accept button (name-based). */
    public static boolean isAcceptButton(ItemStack stack) {
        return nameOf(stack).contains("accept offer");
    }

    /** A visitor menu has both an accept and a refuse button among its own (non-player) slots. */
    public static boolean isVisitorMenu(AbstractContainerMenu menu) {
        boolean accept = false;
        boolean refuse = false;
        for (ItemStack stack : containerStacks(menu)) {
            accept |= isAcceptButton(stack);
            refuse |= isRefuseButton(stack);
        }
        return accept && refuse;
    }

    // ------------------------------------------------------------------ the offer

    /** What the open visitor wants, read from the first container slot whose lore lists it. */
    public static List<Required> requiredItems(AbstractContainerMenu menu) {
        for (ItemStack stack : containerStacks(menu)) {
            List<Required> required = parseRequired(lore(stack));
            if (!required.isEmpty()) {
                return required;
            }
        }
        return List.of();
    }

    /** The {@code Items Required:} block of one item's colour-stripped lore; empty when it has none. */
    public static List<Required> parseRequired(List<String> lore) {
        List<Required> out = new ArrayList<>();
        boolean inBlock = false;
        for (String raw : lore) {
            String line = raw == null ? "" : raw.trim();
            if (!inBlock) {
                inBlock = line.toLowerCase(Locale.ROOT).equals(REQUIRED_HEADER);
                continue;
            }
            if (line.isEmpty() || line.endsWith(":")) {
                break;   // end of the block: a blank line or the next heading ("Rewards:")
            }
            Matcher matcher = ITEM_LINE.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            String name = matcher.group(1).trim();
            int amount = 1;
            if (matcher.group(2) != null) {
                try {
                    amount = Integer.parseInt(matcher.group(2).replace(",", ""));
                } catch (NumberFormatException tooLong) {
                    continue;   // longer than an int: not an amount, and not worth guessing at
                }
            }
            if (!name.isEmpty() && amount > 0) {
                out.add(new Required(name, amount));
            }
        }
        return out;
    }

    /**
     * The lore of the item that carries the offer (the one with the required-items block), or an
     * empty list when no slot has one.
     */
    public static List<String> offerLore(AbstractContainerMenu menu) {
        for (ItemStack stack : containerStacks(menu)) {
            List<String> lore = lore(stack);
            if (!parseRequired(lore).isEmpty()) {
                return lore;
            }
        }
        return List.of();
    }

    /** " +8 Copper" - {@code ESTIMATED} like the rest of the offer block. */
    private static final Pattern COPPER_LINE = Pattern.compile("^\\+?(\\d[\\d,]*)\\s+Copper\\b.*");

    /** The copper an offer pays, from anywhere in its lore; {@code -1} when it names none. */
    public static int parseCopper(List<String> lore) {
        for (String raw : lore) {
            Matcher matcher = COPPER_LINE.matcher(raw == null ? "" : raw.trim());
            if (matcher.matches()) {
                try {
                    return Integer.parseInt(matcher.group(1).replace(",", ""));
                } catch (NumberFormatException tooLong) {
                    return -1;
                }
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ reading slots

    /** The menu's own slots, without the player's inventory below it. */
    static List<ItemStack> containerStacks(AbstractContainerMenu menu) {
        List<ItemStack> out = new ArrayList<>();
        if (menu == null) {
            return out;
        }
        int containerSlots = Math.max(0, menu.slots.size() - 36);
        for (int i = 0; i < containerSlots; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack != null && !stack.isEmpty()) {
                out.add(stack);
            }
        }
        return out;
    }

    static List<String> lore(ItemStack stack) {
        var lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>(lore.lines().size());
        for (Component line : lore.lines()) {
            out.add(strip(line.getString()));
        }
        return out;
    }

    private static String nameOf(ItemStack stack) {
        return stack == null || stack.isEmpty() ? ""
                : strip(stack.getHoverName().getString()).toLowerCase(Locale.ROOT);
    }

    /** {@link #strip}, for callers outside this package. */
    public static String plain(String text) {
        return strip(text);
    }

    /** Colour codes out, char loop rather than a regex: this runs over every slot of a menu. */
    static String strip(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == (char) 0x00A7 && i + 1 < text.length()) {
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
