/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.fishing.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.skills.fishing.logic.GoldenFishTimer;
import sbs.modid.client.skills.fishing.logic.GoldenFishTracker;
import sbs.modid.client.skills.fishing.model.GoldenFishRules;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * The movable Golden Fish card ({@link HudElement#GOLDEN_FISH}). Two lines while fishing -
 * "Lava fishing 6:12" and "Last cast 12s" (warning colour with "resets in Xs" near the limit) - and,
 * while a fish is up, "Golden Fish up · 42s left · hooks 1/3". The hook target and the time left are
 * marked "~" because they come from {@link GoldenFishRules}, which is ESTIMATED.
 */
public final class GoldenFishHud {

    private static final int PAD = 4;
    private static final int WARN = 0xFFFF5555;
    private static final int GOLD = 0xFFFFAA00;

    private GoldenFishHud() {
    }

    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.FishingSettings cfg = ConfigManager.getInstance().get().fishing;
        if (!cfg.enabled || !cfg.goldenFishTimer || HudLayout.isHidden(HudElement.GOLDEN_FISH)) {
            return;
        }
        GoldenFishTimer.View v = GoldenFishTracker.getInstance().view(System.currentTimeMillis());
        if (!v.active()) {
            return;
        }
        List<String> lines = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        if (v.fishUp()) {
            lines.add("Golden Fish up · ~" + GoldenFishTimer.clock(v.fishLeftMs()) + " left · hooks "
                    + v.hooks() + "/~" + GoldenFishRules.HOOKS_TO_CATCH);
            colors.add(GOLD);
        } else {
            lines.add("Lava fishing " + GoldenFishTimer.clock(v.fishingMs())
                    + (v.canSpawn() ? " · can appear" : ""));
            colors.add(v.canSpawn() ? GOLD : SBSTheme.TEXT);
            lines.add("Last cast " + GoldenFishTimer.clock(v.sinceCastMs())
                    + (v.resetWarning() ? " · resets in ~" + GoldenFishTimer.clock(v.resetInMs()) : ""));
            colors.add(v.resetWarning() ? WARN : SBSTheme.TEXT);
        }

        Font font = Minecraft.getInstance().font;
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, font.width(line));
        }
        width += PAD * 2;
        int height = lines.size() * (font.lineHeight + 2) + PAD * 2 - 2;
        HudElement.Bounds b = HudElement.GOLDEN_FISH.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());

        HudLayout.measure(HudElement.GOLDEN_FISH, x, y, width, height);
        HudLayout.begin(g, HudElement.GOLDEN_FISH);
        FishingHudPanel.panel(g, x, y, width, height, FishingHudPanel.alpha(cfg.hudOpacity));
        for (int i = 0; i < lines.size(); i++) {
            g.text(font, Component.literal(lines.get(i)), x + PAD, y + PAD + i * (font.lineHeight + 2),
                    colors.get(i), false);
        }
        HudLayout.end(g);
    }
}
