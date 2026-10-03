/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */
package sbs.modid.client.helper.gamemenu;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import sbs.modid.client.core.util.PlainText;

import java.util.List;

/**
 * Which slot of the lobby's {@code Game Menu} is the SkyBlock button. Rule and captures:
 * {@code docs/skyblock-ui/menus.md}, <i>Game Menu</i>.
 *
 * <p>Identified by what the item <i>is</i>, never by slot 12: the layout differs between lobbies
 * (one capture has no SkyBlock button at all) and moves with updates. A player head whose name
 * starts with {@code SkyBlock} - the name carries the version and update title after it - and
 * whose first lore line is {@code Persistent Game}. The {@code Prototype} anvil lists
 * {@code ∙ SkyBlock} in its lore and must not match, which is why the name, not the lore, carries
 * the word.
 */
public final class GameMenuSkyBlock {

    /** The menu's title, colour-stripped, trimmed and lowercased as {@code MenuFrame} gives it. */
    public static final String TITLE = "game menu";

    private static final String NAME_PREFIX = "SkyBlock";
    private static final String FIRST_LORE = "Persistent Game";

    private GameMenuSkyBlock() {
    }

    /** Whether {@code stack} is the Game Menu's SkyBlock button. */
    public static boolean isSkyBlockButton(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.is(Items.PLAYER_HEAD)) {
            return false; // the id test first: it is the cheap one, and only two heads pass it
        }
        ItemLore lore = stack.get(DataComponents.LORE);
        List<Component> lines = lore == null ? List.of() : lore.lines();
        String firstLore = lines.isEmpty() ? null : lines.getFirst().getString();
        return matches(true, stack.getHoverName().getString(), firstLore);
    }

    /**
     * The rule on plain values, so it can be tested on the captured item data without a game.
     *
     * @param playerHead whether the item is a {@code minecraft:player_head}
     * @param name       the item's display name, colour codes allowed
     * @param firstLore  the first lore line, colour codes allowed, or {@code null} for no lore
     */
    static boolean matches(boolean playerHead, String name, String firstLore) {
        if (!playerHead || name == null || firstLore == null) {
            return false;
        }
        return PlainText.strip(name).trim().startsWith(NAME_PREFIX)
                && PlainText.strip(firstLore).trim().equals(FIRST_LORE);
    }
}
