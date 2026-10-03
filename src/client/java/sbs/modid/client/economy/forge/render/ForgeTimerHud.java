/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.forge.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.economy.forge.logic.ForgeSlotParser;
import sbs.modid.client.economy.forge.logic.ForgeTimers;
import sbs.modid.client.economy.forge.model.ForgeSlotTimer;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.List;

/**
 * The Forge Timers card: each tracked slot, "Mithril Plate · 1h 12m" or "READY", soonest on top.
 *
 * <p>Self-hiding with no slots. When the last menu read is older than the configured age the
 * header says "last seen", because the end times are still right but a slot claimed from another
 * device (or a new item started) is not known until the forge is opened again.
 */
public final class ForgeTimerHud {

    private static final int WIDTH_STEP = 8;
    private static final int MIN_WIDTH = 120;
    private static final int RUNNING = 0xFFFFD65A;
    private static final int READY = 0xFF57D977;

    private ForgeTimerHud() {
    }

    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.ForgeSettings cfg = ConfigManager.getInstance().get().forge;
        if (!cfg.timersEnabled || !cfg.timerCard || HudLayout.isHidden(HudElement.FORGE_TIMERS)) {
            return;
        }
        List<ForgeSlotTimer> timers = ForgeTimers.getInstance().sorted();
        if (timers.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        long newestRead = 0L;
        for (ForgeSlotTimer t : timers) {
            newestRead = Math.max(newestRead, t.readAt);
        }
        String header = "Forge";
        if (now - newestRead > Math.max(1, cfg.timerStaleHours) * 3_600_000L) {
            header += "  §8last seen " + ForgeSlotParser.age(newestRead, now) + " ago";
        }

        Font font = Minecraft.getInstance().font;
        int lineH = font.lineHeight + 2;
        int pad = 5;
        int rows = timers.size();
        String[] left = new String[rows];
        String[] right = new String[rows];
        int contentW = font.width(header);
        for (int i = 0; i < rows; i++) {
            ForgeSlotTimer t = timers.get(i);
            left[i] = t.label();
            right[i] = ForgeSlotTimer.format(t.remainingMs(now));
            contentW = Math.max(contentW, font.width(left[i]) + 12 + font.width(right[i]));
        }
        int width = Math.max(MIN_WIDTH, ((contentW + pad * 2 + WIDTH_STEP - 1) / WIDTH_STEP) * WIDTH_STEP);
        int height = pad * 2 + lineH * (1 + rows) - 2;

        HudElement.Bounds b = HudElement.FORGE_TIMERS.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(HudElement.FORGE_TIMERS, x, y, width, height);

        HudLayout.begin(g, HudElement.FORGE_TIMERS);
        HudCard.draw(g, x, y, width, height);
        int ix = x + pad;
        int rx = x + width - pad;
        int iy = y + pad;
        g.text(font, Component.literal(header), ix, iy, SBSTheme.ACCENT_BRIGHT);
        iy += lineH;
        for (int i = 0; i < rows; i++) {
            boolean ready = timers.get(i).ready(now);
            g.text(font, Component.literal(left[i]), ix, iy, SBSTheme.TEXT_MUTED);
            g.text(font, Component.literal(right[i]), rx - font.width(right[i]), iy, ready ? READY : RUNNING);
            iy += lineH;
        }
        HudLayout.end(g);
    }
}
