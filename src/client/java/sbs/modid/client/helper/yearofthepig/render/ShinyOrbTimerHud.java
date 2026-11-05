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
import sbs.modid.client.ui.hud.render.HudCard;
import net.minecraft.world.entity.LivingEntity;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.helper.yearofthepig.logic.ShinyOrbTracker;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;

import java.util.List;

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

    /**
     * The card as last built, and what for: the orb, the countdown in tenths of a second (the
     * precision it shows), whether it is urgent, and both distances in whole metres. Every one of
     * those is a primitive compared per frame, so nothing is built unless a shown figure moved.
     * Render thread only.
     */
    private static HudCard.Snapshot card;
    private static int builtOrb = -1;
    private static long builtTenths = -1;
    private static boolean builtUrgent;
    private static long builtOrbMetres = -1;
    private static long builtPigMetres = -2;

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
        long remainingMs = orb.remainingMs();
        boolean urgent = remainingMs / 1000.0 <= URGENT_SECONDS;
        // Rounded half-up, as "%.1f" and "%.0f" rounded them.
        long tenths = (remainingMs + 50) / 100;
        long orbMetres = Math.round(player.position().distanceTo(orb.position()));
        LivingEntity pig = tracker.activePig();
        long pigMetres = pig == null ? -1 : Math.round(player.position().distanceTo(pig.position()));
        if (card == null || orb.id() != builtOrb || tenths != builtTenths || urgent != builtUrgent
                || orbMetres != builtOrbMetres || pigMetres != builtPigMetres) {
            builtOrb = orb.id();
            builtTenths = tenths;
            builtUrgent = urgent;
            builtOrbMetres = orbMetres;
            builtPigMetres = pigMetres;
            card = build(font, tenths, urgent, orbMetres, pigMetres);
        }
        HudCard.Snapshot current = card;
        int width = current.width();
        int height = current.height();
        HudLayout.measure(HudElement.SHINY_ORB_TIMER, x, y, width, height);

        SciFiRender.glow(g, x, y, width, height, SBSTheme.HUD_CORNER, SBSTheme.PANEL_GLOW, 2);
        SciFiRender.roundedRect(g, x, y, width, height, SBSTheme.HUD_CORNER,
                urgent ? URGENT_COLOR : SBSTheme.PANEL_BORDER);
        SciFiRender.roundedRectGradient(g, x + 1, y + 1, width - 2, height - 2,
                SBSTheme.HUD_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

        int lineH = font.lineHeight + LINE_GAP;
        int ix = x + PAD;
        int right = x + width - PAD;
        int iy = y + PAD;
        for (HudCard.Line line : current.rows()) {
            HudCard.drawLine(g, font, line, ix, right, iy);
            iy += lineH;
        }
    }

    private static HudCard.Snapshot build(Font font, long tenths, boolean urgent, long orbMetres, long pigMetres) {
        String timer = tenths / 10 + "." + tenths % 10 + "s";
        String orbDistance = orbMetres + "m";
        String pigDistance = pigMetres < 0 ? "?" : pigMetres + "m";

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

        HudCard.Line[] lines = new HudCard.Line[1 + rows.length];
        lines[0] = HudCard.Line.of(font, title, SBSTheme.ACCENT_BRIGHT);
        for (int i = 0; i < rows.length; i++) {
            // Only the countdown carries the urgency colour; the distances stay neutral so the eye
            // goes straight to the number that is actually running out.
            int color = i == 0 ? (urgent ? URGENT_COLOR : OK_COLOR) : SBSTheme.TEXT;
            lines[i + 1] = HudCard.Line.of(font, rows[i][0], SBSTheme.TEXT_MUTED, rows[i][1], color);
        }
        return new HudCard.Snapshot(List.of(lines), width, height, null);
    }
}
