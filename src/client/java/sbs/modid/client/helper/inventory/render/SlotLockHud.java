/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.inventory.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import sbs.modid.client.helper.inventory.logic.SlotLock;

/**
 * Draws the padlock over locked hotbar slots on the in-game HUD, so a lock is visible while playing
 * and not only once the inventory is open.
 *
 * <p>Geometry matches vanilla's hotbar (and {@code CooldownOverlay}): the bar is 182 wide, centred,
 * with 20px between slot origins.
 */
public final class SlotLockHud {

    /** Distance between two hotbar slot origins. */
    private static final int SLOT_PITCH = 20;
    /** Half the hotbar's width, used to find its left edge from the screen centre. */
    private static final int HOTBAR_HALF_WIDTH = 91;
    private static final int ITEM_SIZE = 16;

    private SlotLockHud() {
    }

    /** Called from the HUD hotbar render hook. */
    public static void renderHotbar(GuiGraphicsExtractor g) {
        if (!SlotLock.enabled() || Minecraft.getInstance().player == null) {
            return;
        }
        int leftEdge = g.guiWidth() / 2 - HOTBAR_HALF_WIDTH;
        int y = g.guiHeight() - ITEM_SIZE - 3;
        for (int slot = 0; slot < 9; slot++) {
            if (SlotLock.isLocked(slot)) {
                SlotLockIcon.draw(g, leftEdge + 3 + slot * SLOT_PITCH, y);
            }
        }
    }
}
