/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.yearofthepig.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.helper.yearofthepig.logic.ShinyOrbTracker;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;

import java.util.Locale;

/**
 * The live orb card: how long the activated orb has left, and how far away the pig and the orb are.
 *
 * <p>Separate from the world markers because the 90-second limit is the thing you are racing and it
 * has to be readable without looking at the orb – which is usually behind you while you knock the
 * pig back. Draws only while an orb is actually out, so it costs nothing the rest of the time.
 */
public final class ShinyOrbTimerHud {

    private static final int PAD = 6;
    private static final int LINE_GAP = 3;

    /** Under this many seconds the countdown turns red and starts flashing. */
    private static final int URGENT_SECONDS = 15;

    private static final int URGENT_COLOR = 0xFFFF4040;
    private static final int OK_COLOR = 0xFF57D977;

    private ShinyOrbTimerHud() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        var cfg = ConfigManager.getInstance().get().yearOfThePig;
        ShinyOrbTracker tracker = ShinyOrbTracker.getInstance();
        ShinyOrbTracker.ActiveOrb orb = tracker.activeOrb();
        LocalPlayer player = Minecraft.getInstance().player;
        if (!cfg.enabled || !cfg.showTimer || orb == null || player == null
                || HudLayout.isHidden(HudElement.SHINY_ORB_TIMER)) {
            return;
        }
        HudElement.Bounds bounds = HudElement.SHINY_ORB_TIMER.defaultBounds(g.guiWidth(), g.guiHeight());
        HudLayout.begin(g, HudElement.SHINY_ORB_TIMER);
        draw(g, tracker, orb, player, (int) bounds.x(), (int) bounds.y());
        HudLayout.end(g);
    }

    private static void draw(GuiGraphicsExtractor g, ShinyOrbTracker tracker,
                             ShinyOrbTracker.ActiveOrb orb, LocalPlayer player, int x, int y) {
        Font font = Minecraft.getInstance().font;
        double seconds = orb.remainingMs() / 1000.0;
        boolean urgent = seconds <= URGENT_SECONDS;

        String timer = String.format(Locale.US, "%.1fs", seconds);
        String orbDistance = String.format(Locale.US, "%.0fm", player.position().distanceTo(orb.position()));
        LivingEntity pig = tracker.activePig();
        String pigDistance = pig == null ? "?" : String.format(Locale.US, "%.0fm",
                player.position().distanceTo(pig.position()));

        String title = "Shiny Orb";
        String[][] rows = {
                {"Time left", timer},
                {"Orb", orbDistance},
                {"Pig", pigDistance},
        };

        int lineH = font.lineHeight + LINE_GAP;
        int contentW = font.width(title) + 10;
        for (String[] row : rows) {
            contentW = Math.max(contentW, font.width(row[0]) + 12 + font.width(row[1]));
        }
        int width = Math.max(96, PAD * 2 + contentW);
        int height = PAD * 2 + lineH * (1 + rows.length) - LINE_GAP;
        HudLayout.measure(HudElement.SHINY_ORB_TIMER, x, y, width, height);

        SciFiRender.glow(g, x, y, width, height, SBSTheme.HUD_CORNER, SBSTheme.PANEL_GLOW, 2);
        SciFiRender.roundedRect(g, x, y, width, height, SBSTheme.HUD_CORNER,
                urgent ? URGENT_COLOR : SBSTheme.PANEL_BORDER);
        SciFiRender.roundedRectGradient(g, x + 1, y + 1, width - 2, height - 2,
                SBSTheme.HUD_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

        int ix = x + PAD;
        int right = x + width - PAD;
        int iy = y + PAD;

        g.text(font, Component.literal(title), ix, iy, SBSTheme.ACCENT_BRIGHT);
        iy += lineH;
        for (String[] row : rows) {
            // Only the countdown carries the urgency colour; the distances stay neutral so the eye
            // goes straight to the number that is actually running out.
            int color = row[0].equals("Time left") ? (urgent ? URGENT_COLOR : OK_COLOR) : SBSTheme.TEXT;
            g.text(font, Component.literal(row[0]), ix, iy, SBSTheme.TEXT_MUTED);
            g.text(font, Component.literal(row[1]), right - font.width(row[1]), iy, color);
            iy += lineH;
        }
    }
}
