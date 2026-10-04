/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.experiment.logic;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * Superpairs, the Minecraft side: feeds {@link SuperpairsModel} and draws its answer. The model
 * decides what is under each card, which are matched and what to turn next; see it for the
 * confirmed board encoding.
 *
 * <p>Every card seen is ghosted back onto its cover (faint outline), the card(s) to turn next get a
 * bright outline, and during the "?" pause a "wait" label shows. Highlight only - no click is
 * ever blocked here.
 */
final class SuperpairsSolver {

    private static final int REMEMBERED = 0x66FFE24B;
    private static final int TURN_NEXT = 0xFF55FF55;

    private final SuperpairsModel model = new SuperpairsModel();

    /** Slot -> a copy of the card last seen there, and the live stack it was copied from. */
    private final Map<Integer, ItemStack> icons = new HashMap<>();
    private final Map<Integer, ItemStack> iconSources = new HashMap<>();

    void reset() {
        model.reset();
        icons.clear();
        iconSources.clear();
    }

    void scan(AbstractContainerMenu menu, PlainItem[] board) {
        model.onFrame(board);
        for (int slot : model.memory().keySet()) {
            if (!model.isRevealed(slot) || slot >= menu.slots.size()) {
                continue;
            }
            ItemStack live = menu.getSlot(slot).getItem();
            if (iconSources.get(slot) != live) {
                iconSources.put(slot, live);
                icons.put(slot, live.copyWithCount(1));
            }
        }
    }

    void render(GuiGraphicsExtractor g, Font font, AbstractContainerMenu menu, int left, int top) {
        for (Map.Entry<Integer, ItemStack> entry : icons.entrySet()) {
            int slot = entry.getKey();
            if (slot >= menu.slots.size() || !model.board().contains(slot) || model.isRevealed(slot)
                    || model.isMatched(slot)) {
                continue;   // only ghost onto a card that is face down again
            }
            Slot s = menu.getSlot(slot);
            ExperimentationTable.ghost(g, entry.getValue(), left + s.x, top + s.y);
            ExperimentationTable.outline(g, left + s.x, top + s.y, REMEMBERED);
        }
        for (int slot : model.suggestion()) {
            if (slot < menu.slots.size()) {
                Slot s = menu.getSlot(slot);
                ExperimentationTable.outline(g, left + s.x, top + s.y, TURN_NEXT);
            }
        }
        if (model.phase() == SuperpairsModel.Phase.WAIT) {
            ExperimentationTable.titleLabel(g, font, left, top, "wait", 0xFFFFE24B);
        } else if (model.phase() == SuperpairsModel.Phase.INSTANT) {
            ExperimentationTable.titleLabel(g, font, left, top, "instant", 0xFF55FFFF);
        }
    }

    String debug() {
        return model.debug();
    }
}
