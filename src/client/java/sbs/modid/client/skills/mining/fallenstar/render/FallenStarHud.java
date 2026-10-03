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
import net.minecraft.network.chat.Component;
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

    private FallenStarHud() {
    }

    public static void render(GuiGraphicsExtractor g) {
        FallenStarSettings cfg = ConfigManager.getInstance().get().fallenStar;
        if (!cfg.enabled || !cfg.hud || HudLayout.isHidden(HudElement.FALLEN_STAR)
                || !SkyBlockLocation.onIsland(FallenStarTracker.ISLAND)) {
            return;
        }
        FallenStarTracker tracker = FallenStarTracker.getInstance();
        List<String> lines = new ArrayList<>(2);
        String zone = tracker.zone();
        if (zone != null) {
            long seconds = (System.currentTimeMillis() - tracker.crashedAt()) / 1000;
            lines.add("✯ Fallen Star: " + zone + "  "
                    + String.format(Locale.ROOT, "%d:%02d ago", seconds / 60, seconds % 60));
        }
        if (tracker.cultActive()) {
            lines.add("Cult of the Fallen Star: now");
        }
        if (lines.isEmpty()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        int lineH = font.lineHeight + 2;
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, font.width(line));
        }
        width += PAD * 2;
        int height = PAD * 2 + lineH * lines.size() - 2;
        HudElement.Bounds b = HudElement.FALLEN_STAR.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(HudElement.FALLEN_STAR, x, y, width, height);
        HudLayout.begin(g, HudElement.FALLEN_STAR);
        HudCard.draw(g, x, y, width, height);
        int iy = y + PAD;
        for (String line : lines) {
            g.text(font, Component.literal(line), x + PAD, iy, SBSTheme.ACCENT_BRIGHT);
            iy += lineH;
        }
        HudLayout.end(g);
    }
}
