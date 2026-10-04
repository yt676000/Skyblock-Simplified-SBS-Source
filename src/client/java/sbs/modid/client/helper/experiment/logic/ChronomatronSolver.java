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
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Chronomatron, the Minecraft side: feeds {@link ChronomatronModel} and draws its answer. Every
 * decision - what was flashed, where a show starts, which button is next - is in the model; see it
 * for the confirmed board encoding (glass turns INTO terracotta with the glint while lit).
 *
 * <p>During input every button still to click is outlined (both halves) and numbered from the
 * next one (1 = click now), each highlight vanishing once clicked. Nothing is drawn during the
 * show (the game does not accept clicks yet); the moment input starts, the start cue fires (see
 * {@link StartCue}). The outline colour is by position: 1st,
 * 2nd, 3rd and later, from the Experimentation module (default green, yellow, red).
 */
final class ChronomatronSolver {

    private final ChronomatronModel model = new ChronomatronModel();
    private final StartCue cue = new StartCue();

    void reset() {
        model.reset();
        cue.reset();
    }

    void scan(PlainItem[] board, long now) {
        model.onFrame(board, now);
        cue.update(model.showHints() && model.next() >= 0, now);
    }

    void render(GuiGraphicsExtractor g, Font font, AbstractContainerMenu menu, int left, int top) {
        // Input only: the show is counted but not drawn. passive stops the blocking, never this.
        if (!model.showHints()) {
            return;
        }
        SBSConfig.ExperimentationSettings cfg = ConfigManager.getInstance().get().experimentation;
        ExperimentationTable.startCue(g, font, menu, model.buttonSlots(), cue, left, top);
        List<Integer> line = model.sequence();
        int from = model.clickIndex();
        List<Integer> drawn = new ArrayList<>();
        for (int p = from; p < line.size(); p++) {
            int anchor = line.get(p);
            if (drawn.contains(anchor)) {
                continue;   // a button repeated later in the line keeps its EARLIEST number
            }
            drawn.add(anchor);
            int position = p - from + 1;
            label(g, font, menu, left, top, anchor, colorFor(position, cfg), position, position == 1);
        }
    }

    /** The per-position palette: 1st, 2nd, 3rd and later (set in the Experimentation module). */
    static int colorFor(int position, SBSConfig.ExperimentationSettings cfg) {
        return position <= 1 ? cfg.chronomatronColor1 : position == 2 ? cfg.chronomatronColor2 : cfg.chronomatronColor3;
    }

    /** Outlines every half of the button and writes {@code number} on its anchor. */
    private void label(GuiGraphicsExtractor g, Font font, AbstractContainerMenu menu, int left, int top,
                       int anchor, int color, int number, boolean bright) {
        for (int slot : model.slotsOf(anchor)) {
            if (slot < menu.slots.size()) {
                Slot s = menu.getSlot(slot);
                ExperimentationTable.outline(g, left + s.x, top + s.y, color);
            }
        }
        if (anchor < menu.slots.size()) {
            Slot s = menu.getSlot(anchor);
            g.text(font, Component.literal(String.valueOf(number)), left + s.x + 1, top + s.y + 1,
                    bright ? 0xFFFFFFFF : 0xFFBBBBBB);
        }
    }

    boolean allowsClick(int index) {
        return model.allowsClick(index, System.currentTimeMillis());
    }

    void onClick(int index) {
        model.onClick(index, System.currentTimeMillis());
    }

    String debug() {
        return model.debug();
    }
}
