/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.floorseven.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.dungeons.floorseven.logic.FloorSevenPhaseTimer;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.List;

/**
 * The F7 / M7 phase-split card: one line per finished phase, the running phase in the accent colour,
 * and the total. Stays on screen with the final splits after the run ends, until the player leaves
 * the Catacombs. Self-measuring and movable via the GUI editor.
 */
public final class FloorSevenPhaseHud {

    private static final int PAD = 6;
    private static final int LINE_GAP = 3;
    private static final int MIN_W = 130;
    private static final String SECTION_SIGN = String.valueOf((char) 0x00A7);

    private FloorSevenPhaseHud() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        var cfg = ConfigManager.getInstance().get().dungeons;
        if (!cfg.phaseTimer || Minecraft.getInstance().player == null
                || HudLayout.isHidden(HudElement.F7_PHASE_TIMER)) {
            return;
        }
        FloorSevenPhaseTimer timer = FloorSevenPhaseTimer.getInstance();
        if (!timer.active()) {
            return;
        }
        HudElement.Bounds bounds = HudElement.F7_PHASE_TIMER.defaultBounds(g.guiWidth(), g.guiHeight());
        HudLayout.begin(g, HudElement.F7_PHASE_TIMER);
        draw(g, timer, (int) bounds.x(), (int) bounds.y());
        HudLayout.end(g);
    }

    private static void draw(GuiGraphicsExtractor g, FloorSevenPhaseTimer timer, int x, int y) {
        Font font = Minecraft.getInstance().font;

        List<FloorSevenPhaseTimer.Split> splits = timer.splits();
        List<String> lines = new java.util.ArrayList<>();
        lines.add("Phases  §7" + (timer.finished() ? "done" : "live"));
        for (FloorSevenPhaseTimer.Split split : splits) {
            lines.add("§7" + split.phase().label() + " §f" + FloorSevenPhaseTimer.time(split.durationMs()));
        }
        FloorSevenPhaseTimer.Phase current = timer.current();
        if (current != null) {
            lines.add("§b" + current.label() + " §f" + FloorSevenPhaseTimer.time(timer.currentMs()));
        }
        lines.add("§7Total §f" + FloorSevenPhaseTimer.time(timer.totalMs()));

        int contentW = 0;
        for (String line : lines) {
            contentW = Math.max(contentW, font.width(strip(line)));
        }
        int width = Math.max(MIN_W, PAD * 2 + contentW);
        int lineH = font.lineHeight + LINE_GAP;
        int height = PAD * 2 + lineH * lines.size() - LINE_GAP;
        HudLayout.measure(HudElement.F7_PHASE_TIMER, x, y, width, height);

        HudCard.draw(g, x, y, width, height);

        int ix = x + PAD;
        int iy = y + PAD;
        for (int i = 0; i < lines.size(); i++) {
            g.text(font, Component.literal(lines.get(i)), ix, iy,
                    i == 0 ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT);
            iy += lineH;
        }
    }

    private static String strip(String text) {
        return text.replaceAll(SECTION_SIGN + ".", "");
    }
}
