/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.garden.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.skills.garden.logic.VisitorTimer;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.theme.SBSTheme;

/**
 * The Visitor Timer card: "Visitors 3/5 · next in 2m 40s", or "Queue full" in the warning colour.
 * Garden only; with the Visitors widget switched off it says how to turn it back on.
 */
public final class VisitorTimerHud {

    /** The same amber the pest cooldown card uses for "act now". */
    private static final int WARNING = 0xFFE0A14D;

    private VisitorTimerHud() {
    }

    /** From the HUD hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        VisitorTimer timer = VisitorTimer.getInstance();
        if (!ConfigManager.getInstance().get().gardenHelpers.visitorTimer
                || HudLayout.isHidden(HudElement.VISITOR_TIMER)
                || Minecraft.getInstance().player == null || !timer.onGarden()) {
            return;
        }
        VisitorTimer.Row row = timer.row();
        String text = VisitorTimer.cardText(row, timer.count(), timer.cap(), timer.remainingMs());
        boolean full = row != null && row.kind() == VisitorTimer.Kind.FULL;

        Font font = Minecraft.getInstance().font;
        int pad = 5;
        int width = Math.max(110, font.width(text) + pad * 2);
        int height = pad * 2 + font.lineHeight;

        HudElement.Bounds b = HudElement.VISITOR_TIMER.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(HudElement.VISITOR_TIMER, x, y, width, height);
        HudLayout.begin(g, HudElement.VISITOR_TIMER);
        HudCard.draw(g, x, y, width, height);
        g.text(font, Component.literal(text), x + pad, y + pad,
                full ? WARNING : row == null ? SBSTheme.TEXT_MUTED : SBSTheme.TEXT);
        HudLayout.end(g);
    }
}
