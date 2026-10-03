/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.chest;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import sbs.modid.client.ui.render.SlotDecorator;

/**
 * Registers the reward-chest marks. Everything they mean, and all the gating, lives in
 * {@link DungeonChestOverlay}.
 *
 * <p><b>No title test, and that is what makes this work at Croesus.</b> A chest is recognised by the
 * shape of its own tooltip - a {@code Contents} listing followed by a {@code Cost} - so the marks
 * appear wherever Hypixel shows a reward chest: the menu after a boss, and the run list at the NPC,
 * which was never written for and needed nothing adding. {@link #tier} is therefore left at
 * {@code RELEVANT}: there is no cheap title that says "chests are in here", and guessing one would
 * turn the feature off in the menu nobody thought to name.
 */
public final class DungeonChestDecorator implements SlotDecorator {

    @Override
    public String id() {
        return "dungeon_chest";
    }

    @Override
    public int order() {
        return 700;
    }

    @Override
    public void decorate(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g,
                         int mouseX, int mouseY) {
        DungeonChestOverlay.render(screen, g);
    }
}
