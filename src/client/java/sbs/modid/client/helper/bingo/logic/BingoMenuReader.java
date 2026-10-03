/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.bingo.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.util.PlainText;
import sbs.modid.client.helper.bingo.model.BingoCard;
import sbs.modid.client.ui.render.MenuFrame;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the Bingo Card menu whenever the server changes its contents, and stores the card. Read-only:
 * it looks at the slots and never clicks.
 *
 * <p>Gated like the Museum reader: one state-id comparison per tick while a menu is open, a read only
 * when the title matches ({@code ESTIMATED}, see {@link BingoCardParser}) and the contents changed.
 * Each read logs one line - goal count, done count, community count - so a wrong grid or a wrong
 * marker shows in the first visit's log instead of as an empty card.
 */
public final class BingoMenuReader {

    private static final BingoMenuReader INSTANCE = new BingoMenuReader();

    private AbstractContainerScreen<?> lastScreen;
    private int lastState = -1;

    private BingoMenuReader() {
    }

    public static BingoMenuReader getInstance() {
        return INSTANCE;
    }

    public void tick(Minecraft minecraft) {
        if (!ConfigManager.getInstance().get().bingo.enabled) {
            lastScreen = null;
            return;
        }
        // Also what brings the store (and its chat patterns) to life before the first menu visit.
        BingoStore store = BingoStore.getInstance();
        BingoProfile.getInstance().tick(minecraft);
        if (!(GuiStateManager.getInstance().getCurrentScreen() instanceof AbstractContainerScreen<?> screen)) {
            lastScreen = null;
            return;
        }
        int state = screen.getMenu().getStateId();
        if (screen == lastScreen && state == lastState) {
            return;
        }
        lastScreen = screen;
        lastState = state;
        if (!MenuFrame.of(screen).normalised().contains(BingoCardParser.TITLE_KEY)) {
            return;
        }
        List<BingoCardParser.SlotView> slots = new ArrayList<>();
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container instanceof Inventory || !slot.hasItem()) {
                continue;
            }
            ItemStack stack = slot.getItem();
            slots.add(new BingoCardParser.SlotView(slot.index,
                    BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                    PlainText.strip(stack.getHoverName().getString()).trim(),
                    lore(stack)));
        }
        List<BingoCard.Goal> goals = BingoCardParser.parse(slots);
        if (goals.isEmpty()) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Bingo] card read: no goal on the grid ({} slots) - "
                    + "the grid or title guess is wrong, see docs/features/bingo-card-overlay.md", slots.size());
            return;   // never replace a stored card with an empty read
        }
        long done = goals.stream().filter(g -> g.done).count();
        long community = goals.stream().filter(g -> g.community).count();
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Bingo] card read: {} goals, {} done, {} community",
                goals.size(), done, community);
        store.record(new BingoCard(System.currentTimeMillis(), goals));
    }

    private static List<String> lore(ItemStack stack) {
        var lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>(lore.lines().size());
        for (Component line : lore.lines()) {
            lines.add(PlainText.strip(line.getString()));
        }
        return lines;
    }
}
