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
import sbs.modid.client.dungeons.floorseven.logic.TerminalTracker;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Goldor-gate card: terminals, devices and levers done against their totals, and the three
 * players who did the most of them - your own line in the accent colour, so "am I pulling my weight"
 * is answered without counting chat lines. A finished counter goes green.
 *
 * <p>Reads {@link TerminalTracker} only; it measures nothing itself. Self-measuring and movable via
 * the GUI editor, like every other dungeon card.
 */
public final class TerminalProgressHud {

    private static final int PAD = 6;
    private static final int LINE_GAP = 3;
    private static final int MIN_W = 132;
    private static final int TOP_PLAYERS = 3;
    private static final String SECTION_SIGN = String.valueOf((char) 0x00A7);

    private TerminalProgressHud() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        if (!ConfigManager.getInstance().get().dungeons.terminalProgress
                || Minecraft.getInstance().player == null
                || HudLayout.isHidden(HudElement.TERMINAL_PROGRESS)) {
            return;
        }
        TerminalTracker tracker = TerminalTracker.getInstance();
        if (!tracker.active()) {
            return;
        }
        HudElement.Bounds bounds = HudElement.TERMINAL_PROGRESS.defaultBounds(g.guiWidth(), g.guiHeight());
        HudLayout.begin(g, HudElement.TERMINAL_PROGRESS);
        draw(g, tracker, (int) bounds.x(), (int) bounds.y());
        HudLayout.end(g);
    }

    private static void draw(GuiGraphicsExtractor g, TerminalTracker tracker, int x, int y) {
        Font font = Minecraft.getInstance().font;

        List<String> lines = new ArrayList<>();
        lines.add("Gate  §7" + (tracker.complete() ? "§aopen" : "running"));
        for (Map.Entry<TerminalTracker.Kind, TerminalTracker.Progress> entry : tracker.counters()) {
            TerminalTracker.Progress value = entry.getValue();
            boolean done = value.done() >= value.total();
            lines.add("§7" + entry.getKey().label() + " " + (done ? "§a" : "§f")
                    + value.done() + "§7/" + value.total());
        }
        String self = TerminalTracker.selfName();
        int shown = 0;
        for (Map.Entry<String, Integer> entry : tracker.contributors()) {
            if (shown++ >= TOP_PLAYERS) {
                break;
            }
            boolean mine = entry.getKey().toLowerCase(Locale.ROOT).equals(self);
            lines.add((mine ? "§b" : "§7") + entry.getKey() + " §f" + entry.getValue());
        }

        int contentW = 0;
        for (String line : lines) {
            contentW = Math.max(contentW, font.width(strip(line)));
        }
        int width = Math.max(MIN_W, PAD * 2 + contentW);
        int lineH = font.lineHeight + LINE_GAP;
        int height = PAD * 2 + lineH * lines.size() - LINE_GAP;
        HudLayout.measure(HudElement.TERMINAL_PROGRESS, x, y, width, height);

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
