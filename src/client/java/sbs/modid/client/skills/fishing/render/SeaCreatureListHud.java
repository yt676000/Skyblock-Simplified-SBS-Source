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
import net.minecraft.world.entity.player.Player;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.skills.fishing.logic.FishingHudVisibility;
import sbs.modid.client.skills.fishing.logic.FishingTracker;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Sea Creature Tracker as its own clean list on the left of the screen: every sea creature you
 * caught this session with its count, biggest
 * first, plus the session total – separate from the {@link FishingHud} panel so it can sit where the
 * eye expects the list without dragging the catches/profit sections along.
 *
 * <p>Rides on {@link HudElement#SEA_CREATURE_LIST} for the GUI editor. Shows while the module +
 * tracker are on and the shared "Tracker Visibility" setting ({@link FishingHudVisibility}) allows
 * it.
 */
public final class SeaCreatureListHud {

    private static final int PAD = 5;
    private static final int LINE_GAP = 2;
    /** Longest list drawn before the rest is folded into a "+n more" row. */
    private static final int MAX_ROWS = 25;
    private static final int MIN_WIDTH = 104;

    private static final int COUNT_COLOR = 0xFFFFFFFF;
    private static final int NAME_COLOR = 0xFF6FD9FF;

    private SeaCreatureListHud() {
    }

    private static SBSConfig.FishingSettings cfg() {
        return ConfigManager.getInstance().get().fishing;
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.FishingSettings cfg = cfg();
        if (!cfg.enabled || !cfg.seaCreatureTracker || HudLayout.isHidden(HudElement.SEA_CREATURE_LIST)) {
            return;
        }
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        FishingTracker tracker = FishingTracker.getInstance();
        Map<String, Integer> creatures = tracker.creatures();
        if (!cfg.hudVisibility().shouldRender(player, !creatures.isEmpty())) {
            return;
        }

        // Biggest counts first, capped, remainder folded into one row.
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(creatures.entrySet());
        sorted.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        int shown = Math.min(sorted.size(), MAX_ROWS);

        Font font = Minecraft.getInstance().font;
        int lineH = font.lineHeight + LINE_GAP;
        int rows = Math.max(1, shown + (sorted.size() > shown ? 1 : 0)) + 2; // header + rows + total

        int contentW = MIN_WIDTH;
        for (int i = 0; i < shown; i++) {
            var entry = sorted.get(i);
            contentW = Math.max(contentW, font.width(fmt(entry.getValue()) + "  " + entry.getKey()));
        }
        int width = contentW + PAD * 2;
        int height = PAD * 2 + rows * lineH - LINE_GAP;

        HudElement.Bounds b = HudElement.SEA_CREATURE_LIST.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        int alpha = FishingHudPanel.alpha(cfg.hudOpacity);

        HudLayout.measure(HudElement.SEA_CREATURE_LIST, x, y, width, height);
        HudLayout.begin(g, HudElement.SEA_CREATURE_LIST);
        FishingHudPanel.panel(g, x, y, width, height, alpha);

        int ty = y + PAD;
        g.text(font, Component.literal("Sea Creature Tracker"), x + PAD, ty, SBSTheme.ACCENT, false);
        ty += lineH;
        if (shown == 0) {
            g.text(font, Component.literal("none yet"), x + PAD, ty, SBSTheme.TEXT_MUTED, false);
            ty += lineH;
        }
        for (int i = 0; i < shown; i++) {
            var entry = sorted.get(i);
            String count = fmt(entry.getValue());
            g.text(font, Component.literal(count), x + PAD, ty, COUNT_COLOR, false);
            g.text(font, Component.literal(entry.getKey()),
                    x + PAD + font.width(count) + 4, ty, NAME_COLOR, false);
            ty += lineH;
        }
        if (sorted.size() > shown) {
            g.text(font, Component.literal("+" + (sorted.size() - shown) + " more"),
                    x + PAD, ty, SBSTheme.TEXT_MUTED, false);
            ty += lineH;
        }
        g.text(font, Component.literal(fmt(tracker.totalCreatures()) + " Total Sea Creatures"),
                x + PAD, ty, SBSTheme.TEXT_MUTED, false);
        HudLayout.end(g);
    }

    /** 12345 -> "12.345" - the thousands-dotted count style of the reference list. */
    private static String fmt(int value) {
        return String.format(Locale.GERMAN, "%,d", value);
    }
}
