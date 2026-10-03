/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.slayer.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import sbs.modid.client.combat.slayer.logic.SlayerTracker;
import sbs.modid.client.core.config.ConfigManager;

/**
 * A yellow ring around the hotbar slot holding the dagger the Inferno fight is asking for.
 *
 * <p>The alert above the crosshair says which mode is needed; this says where it lives. The two
 * answer different halves of the same second – knowing the shield is on Ashen is no use if you then
 * have to look down and work out which of the two daggers that was.
 *
 * <p>Marks the dagger of the needed PAIR, flipped correctly or not: either way that is the slot to
 * press, because the flip itself is a right-click once it is in your hand.
 *
 * <p>Geometry matches vanilla's hotbar (and {@code SlotLockHud} / {@code CooldownOverlay}): the bar is
 * 182 wide, centred, 20px between slot origins. Drawn from inside the hotbar's own render hook, so it
 * follows the bar wherever the GUI editor has put it.
 */
public final class BlazeDaggerHighlight {

    /** Distance between two hotbar slot origins. */
    private static final int SLOT_PITCH = 20;
    /** Half the hotbar's width, used to find its left edge from the screen centre. */
    private static final int HOTBAR_HALF_WIDTH = 91;
    private static final int ITEM_SIZE = 16;
    /**
     * How far outside the icon the ring sits. The gap between two hotbar icons is exactly 4px, so at
     * 2 the ring fills that gap and covers none of the item it is pointing at.
     */
    private static final int INSET = 2;
    private static final int THICKNESS = 2;
    private static final int BOX_COLOR = 0xFFFFD024;

    private BlazeDaggerHighlight() {
    }

    /** Called from the HUD hotbar render hook, at the tail so it sits over the item icons. */
    public static void renderHotbar(GuiGraphicsExtractor g) {
        var cfg = ConfigManager.getInstance().get().slayer;
        SlayerTracker tracker = SlayerTracker.getInstance();
        if (!cfg.enabled || !cfg.daggerHighlight || Minecraft.getInstance().player == null
                || !tracker.attunementFresh()) {
            return;
        }
        int slot = tracker.daggerSlot();
        if (slot < 0) {
            return;
        }
        int x = g.guiWidth() / 2 - HOTBAR_HALF_WIDTH + 3 + slot * SLOT_PITCH - INSET;
        int y = g.guiHeight() - ITEM_SIZE - 3 - INSET;
        int size = ITEM_SIZE + INSET * 2;

        g.fill(x, y, x + size, y + THICKNESS, BOX_COLOR);
        g.fill(x, y + size - THICKNESS, x + size, y + size, BOX_COLOR);
        g.fill(x, y + THICKNESS, x + THICKNESS, y + size - THICKNESS, BOX_COLOR);
        g.fill(x + size - THICKNESS, y + THICKNESS, x + size, y + size - THICKNESS, BOX_COLOR);
    }
}
