/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.fallenstar.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig.FallenStarSettings;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.skills.mining.fallenstar.logic.FallenStarTracker;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One or two lines while a Fallen Star is up (zone, time since the crash) or the Cult is on. */
public final class FallenStarHud {

    private static final int PAD = 5;

    /** The card as last built, {@code null} with nothing up. Render thread only. */
    private static HudCard.Snapshot card;
    private static String builtZone;
    private static long builtSeconds = -1;
    private static boolean builtCult;

    private FallenStarHud() {
    }

    public static void render(GuiGraphicsExtractor g) {
        FallenStarSettings cfg = ConfigManager.getInstance().get().fallenStar;
        if (!cfg.enabled || !cfg.hud || HudLayout.isHidden(HudElement.FALLEN_STAR)
                || !SkyBlockLocation.onIsland(FallenStarTracker.ISLAND)) {
            return;
        }
        FallenStarTracker tracker = FallenStarTracker.getInstance();
        // The tracker's whole displayed state is three fields, so they are the key themselves; the
        // elapsed second is the clock, at the precision the "m:ss ago" shows.
        String zone = tracker.zone();
        long seconds = zone == null ? -1 : (System.currentTimeMillis() - tracker.crashedAt()) / 1000;
        boolean cult = tracker.cultActive();
        if (zone != builtZone || seconds != builtSeconds || cult != builtCult) {
            builtZone = zone;
            builtSeconds = seconds;
            builtCult = cult;
            card = build(zone, seconds, cult);
        }
        HudCard.Snapshot current = card;
        if (current == null) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        int lineH = font.lineHeight + 2;
        HudElement.Bounds b = HudElement.FALLEN_STAR.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(HudElement.FALLEN_STAR, x, y, current.width(), current.height());
        HudLayout.begin(g, HudElement.FALLEN_STAR);
        HudCard.draw(g, x, y, current.width(), current.height());
        int iy = y + PAD;
        for (HudCard.Line line : current.rows()) {
            HudCard.drawLine(g, font, line, x + PAD, x + current.width() - PAD, iy);
            iy += lineH;
        }
        HudLayout.end(g);
    }

    private static HudCard.Snapshot build(String zone, long seconds, boolean cult) {
        Font font = Minecraft.getInstance().font;
        List<HudCard.Line> lines = new ArrayList<>(2);
        if (zone != null) {
            lines.add(HudCard.Line.of(font, "✯ Fallen Star: " + zone + "  "
                    + String.format(Locale.ROOT, "%d:%02d ago", seconds / 60, seconds % 60),
                    SBSTheme.ACCENT_BRIGHT));
        }
        if (cult) {
            lines.add(HudCard.Line.of(font, "Cult of the Fallen Star: now", SBSTheme.ACCENT_BRIGHT));
        }
        if (lines.isEmpty()) {
            return null;
        }
        int width = 0;
        for (HudCard.Line line : lines) {
            width = Math.max(width, line.leftWidth());
        }
        width += PAD * 2;
        int height = PAD * 2 + (font.lineHeight + 2) * lines.size() - 2;
        return new HudCard.Snapshot(List.copyOf(lines), width, height, null);
    }
}
