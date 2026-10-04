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
import sbs.modid.client.core.config.ConfigManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ultrasequencer, the Minecraft side: feeds {@link UltrasequencerModel} and draws its answer. Every
 * decision - which slot holds which number, what is next - is in the model; see it for the
 * confirmed board encoding.
 *
 * <p>Each tile keeps Hypixel's own number for the whole round; the next one is bright, the rest
 * dark (or shaded by order), clicked tiles drop out. While the board hides the numbers, the tile is
 * ghosted back from a copy taken while it was visible.
 */
final class UltrasequencerSolver {

    /** Light green for the tile to click next; dark green for the rest of the sequence. */
    private static final int NEXT_GREEN = 0xFF55FF55;
    private static final int REST_GREEN = 0xFF1E7D1E;

    private final UltrasequencerModel model = new UltrasequencerModel();
    private final StartCue cue = new StartCue();

    /** Slot -> a copy of the tile while the board covers it, and the live stack it was copied from. */
    private final Map<Integer, ItemStack> icons = new HashMap<>();
    private final Map<Integer, ItemStack> iconSources = new HashMap<>();

    void reset() {
        model.reset();
        cue.reset();
        icons.clear();
        iconSources.clear();
    }

    void scan(AbstractContainerMenu menu, PlainItem[] board) {
        model.onFrame(board);
        cue.update(model.showHints() && model.nextSlot() >= 0, System.currentTimeMillis());
        Map<Integer, Integer> numbers = model.numbers();
        icons.keySet().retainAll(numbers.keySet());
        iconSources.keySet().retainAll(numbers.keySet());
        for (int slot : numbers.keySet()) {
            PlainItem item = slot < board.length ? board[slot] : null;
            if (item == null || item.numericName() != numbers.get(slot)) {
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
        // Input only (the tiles are on show before that); passive stops the blocking, never this.
        if (!model.showHints() || model.numbers().isEmpty()) {
            return;
        }
        ExperimentationTable.startCue(g, font, menu, model.areaSlots(menu.slots.size()), cue, left, top);
        boolean gradient = ConfigManager.getInstance().get().experimentation.ultrasequencerOrderGradient;
        int next = model.nextSlot();
        List<Integer> order = model.clickOrder();
        int total = order.size();
        for (int slot : order) {
            if (model.isClicked(slot) || slot >= menu.slots.size()) {
                continue;
            }
            int number = model.numbers().get(slot);
            Slot s = menu.getSlot(slot);
            int x = left + s.x;
            int y = top + s.y;
            ItemStack icon = icons.get(slot);
            if (icon != null && ExperimentationTable.isFiller(s.getItem())) {
                ExperimentationTable.ghost(g, icon, x, y);
            }
            boolean isNext = slot == next;
            int color = isNext ? NEXT_GREEN : gradient ? gradientGreen(number - 1, total) : REST_GREEN;
            ExperimentationTable.outline(g, x, y, color);
            g.text(font, Component.literal(String.valueOf(number)), x + 1, y + 1,
                    isNext ? 0xFFFFFFFF : 0xFFBBBBBB);
        }
    }

    /** A green whose brightness fades from the front of the sequence to the back (gradient mode). */
    private static int gradientGreen(int position, int total) {
        float t = total <= 1 ? 0f : Math.min(1f, (float) position / (total - 1));   // 0 front, 1 back
        int g = Math.round(0xFF - t * (0xFF - 0x33));   // 255 -> 51
        return 0xFF000000 | (g << 8);
    }

    boolean allowsClick(int index) {
        return model.allowsClick(index);
    }

    void onClick(int index) {
        model.onClick(index);
    }

    String debug() {
        return model.debug();
    }
}
