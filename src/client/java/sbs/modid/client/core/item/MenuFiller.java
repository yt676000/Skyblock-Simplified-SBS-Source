/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.item;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import sbs.modid.client.core.util.PlainText;

/**
 * Hypixel's menu filler: the glass panes that pad a SkyBlock menu out to its frame and carry no
 * meaning of their own. The one definition of it, shared by everything that has to tell
 * decoration from content (the Chocolate Factory shortcut, the storage index, the gradient panes).
 *
 * <p><b>A filler is a glass pane with a blank name and no lore.</b> The name and the lore are the
 * veto, and both are needed: a status pane ("Enabled", a Bazaar order state, a terminal cell) has a
 * name, and a pane whose name is blank but which explains something in its lore is still a control.
 * The colour of a pane is never part of the test - green and red panes are exactly the ones that
 * mean on and off, and they are told apart from filler by what they say, not by their dye.
 *
 * <p>The stack overload is cached per stack ({@link StackCache}, validated against the name and lore
 * components), so asking for every slot of a menu every frame costs a map read once the menu has
 * been seen. Render/client thread only, like the cache; {@link #isPane} and the string overloads
 * are pure and safe anywhere.
 */
public final class MenuFiller {

    /** {@link #dyeOf} for a pane that is not stained: plain {@code glass_pane}. */
    public static final int CLEAR = 16;

    /** {@link #dyeOf} for anything that is not a glass pane at all. */
    public static final int NOT_PANE = -1;

    /**
     * Dye names in the order of {@code DyeColor}'s ids. The index is what {@link #dyeOf} returns and
     * what a per-dye colour table is indexed by.
     */
    private static final String[] DYES = {
            "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};

    private static final String STAINED_SUFFIX = "_stained_glass_pane";
    private static final String PANE = "glass_pane";

    /** Per stack: the dye index of a filler pane, or {@link #NOT_PANE} for anything else. */
    private static final StackCache<Integer> FILLER_DYE = new StackCache<>(256, MenuFiller::computeDye,
            DataComponents.CUSTOM_NAME, DataComponents.ITEM_NAME, DataComponents.LORE);

    private MenuFiller() {
    }

    /** Whether the stack is a glass pane of any colour, whatever it says. */
    public static boolean isPane(ItemStack stack) {
        return stack != null && !stack.isEmpty() && isPanePath(path(stack));
    }

    /** Whether a registry path names a glass pane ({@code glass_pane}, {@code red_stained_glass_pane}). */
    public static boolean isPanePath(String itemPath) {
        return itemPath != null && itemPath.endsWith(PANE);
    }

    /**
     * Whether a slot holds filler, from its parts.
     *
     * @param itemPath  the item's registry path, e.g. {@code black_stained_glass_pane}
     * @param plainName the hover name with colour codes stripped
     * @param loreLines how many lore lines the item carries
     */
    public static boolean isFiller(String itemPath, String plainName, int loreLines) {
        return isPanePath(itemPath)
                && (plainName == null || plainName.isBlank())
                && loreLines == 0;
    }

    /** Whether the stack is filler. Cached per stack; render/client thread only. */
    public static boolean isFiller(ItemStack stack) {
        return fillerDye(stack) != NOT_PANE;
    }

    /**
     * The dye of a filler pane - {@code 0..15} in {@code DyeColor} order, {@link #CLEAR} for plain
     * glass - or {@link #NOT_PANE} when the stack is not filler. Cached per stack; render/client
     * thread only.
     */
    public static int fillerDye(ItemStack stack) {
        // A count is a number being shown. Checked outside the cache: the count is a field the
        // server can change in place, not a component the cache's stamp would notice.
        if (stack == null || stack.isEmpty() || stack.getCount() != 1) {
            return NOT_PANE;
        }
        return FILLER_DYE.get(stack);
    }

    /**
     * The dye a pane's registry path names: {@code 0..15}, {@link #CLEAR} for plain glass, or
     * {@link #NOT_PANE} for a path that is not a pane.
     */
    public static int dyeOf(String itemPath) {
        if (!isPanePath(itemPath)) {
            return NOT_PANE;
        }
        if (itemPath.equals(PANE)) {
            return CLEAR;
        }
        if (!itemPath.endsWith(STAINED_SUFFIX)) {
            return NOT_PANE;
        }
        String dye = itemPath.substring(0, itemPath.length() - STAINED_SUFFIX.length());
        for (int i = 0; i < DYES.length; i++) {
            if (DYES[i].equals(dye)) {
                return i;
            }
        }
        return NOT_PANE;
    }

    private static Integer computeDye(ItemStack stack) {
        String path = path(stack);
        ItemLore lore = stack.get(DataComponents.LORE);
        boolean filler = isFiller(path, PlainText.strip(stack.getHoverName().getString()),
                lore == null ? 0 : lore.lines().size());
        if (!filler) {
            return NOT_PANE;
        }
        int dye = dyeOf(path);
        return dye == NOT_PANE ? CLEAR : dye; // a pane whose dye we do not know is still filler
    }

    private static String path(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    }
}
