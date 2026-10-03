/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.inventory.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.helper.inventory.logic.FreeSlotWatcher;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;

/**
 * The "Free slots: N" chip, drawn only while the inventory is at or below the player's warning
 * threshold.
 *
 * <p>Hidden the rest of the time on purpose: a counter that is always on screen stops being read
 * after the first hour, and the number is only worth a pixel when it is about to matter. The alert
 * is the thing that gets attention; this is what the player looks at afterwards to see how bad it
 * is.
 *
 * <p>Movable and scalable through the GUI editor like every other element.
 */
public final class FreeSlotsHud {

    private static final int PAD = 5;
    private static final int MIN_W = 74;

    /** Nothing free at all - the state the warning is really about. */
    private static final int FULL_COLOR = 0xFFFF6B6B;
    /** At or under the threshold, but not empty yet. */
    private static final int LOW_COLOR = 0xFFFFC85C;

    private FreeSlotsHud() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        var cfg = ConfigManager.getInstance().get().fullInventory;
        if (!cfg.enabled || !cfg.showHud || Minecraft.getInstance().player == null
                || HudLayout.isHidden(HudElement.FREE_SLOTS)) {
            return;
        }
        FreeSlotWatcher watcher = FreeSlotWatcher.getInstance();
        if (!watcher.low()) {
            return; // above the threshold: the chip has nothing to say
        }
        HudElement.Bounds bounds = HudElement.FREE_SLOTS.defaultBounds(g.guiWidth(), g.guiHeight());
        HudLayout.begin(g, HudElement.FREE_SLOTS);
        draw(g, watcher.freeSlots(), (int) bounds.x(), (int) bounds.y());
        HudLayout.end(g);
    }

    private static void draw(GuiGraphicsExtractor g, int free, int x, int y) {
        Font font = Minecraft.getInstance().font;

        String text = "Free slots: " + free;
        int width = Math.max(MIN_W, PAD * 2 + font.width(text));
        int height = PAD * 2 + font.lineHeight;
        // The editor's drag box is this rectangle, so it is measured from what is actually drawn
        // rather than from the nominal bounds above.
        HudLayout.measure(HudElement.FREE_SLOTS, x, y, width, height);

        HudCard.draw(g, x, y, width, height);
        g.text(font, Component.literal(text), x + PAD, y + PAD,
                free == 0 ? FULL_COLOR : LOW_COLOR);
    }
}
