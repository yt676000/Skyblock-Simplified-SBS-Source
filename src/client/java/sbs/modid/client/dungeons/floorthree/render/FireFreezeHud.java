/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.floorthree.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.dungeons.floorthree.logic.FireFreezeTimer;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * The F3 / M3 Fire Freeze card: the countdown to the cast, then the staff's wind-up, then how long
 * the Professor stays frozen. Movable via the GUI editor, self-measuring, and drawn only while the
 * fight is actually in that window.
 *
 * <p>The three stages are one number at a time on purpose. Mid-fight there is exactly one thing
 * worth knowing - "cast now" before the cast, "it lands in a moment" after it, "he is loose in three
 * seconds" once he is held - and a card showing all three at once would need reading rather than
 * glancing at.
 */
public final class FireFreezeHud {

    private static final int PAD = 6;
    private static final int LINE_GAP = 3;
    private static final int MIN_W = 128;

    /** Cast row red - it is a "do it now". The rest is ordinary readout text. */
    private static final int CAST_COLOR = 0xFFFF5555;
    private static final int FROZEN_COLOR = 0xFF6ED8FF;
    private static final String SECTION_SIGN = String.valueOf((char) 0x00A7);

    private FireFreezeHud() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        var cfg = ConfigManager.getInstance().get().dungeons;
        if (!cfg.fireFreezeTimer || Minecraft.getInstance().player == null
                || HudLayout.isHidden(HudElement.FIRE_FREEZE)) {
            return;
        }
        FireFreezeTimer timer = FireFreezeTimer.getInstance();
        if (!timer.active()) {
            return;
        }
        HudElement.Bounds bounds = HudElement.FIRE_FREEZE.defaultBounds(g.guiWidth(), g.guiHeight());
        HudLayout.begin(g, HudElement.FIRE_FREEZE);
        draw(g, timer, (int) bounds.x(), (int) bounds.y());
        HudLayout.end(g);
    }

    private static void draw(GuiGraphicsExtractor g, FireFreezeTimer timer, int x, int y) {
        Font font = Minecraft.getInstance().font;
        FireFreezeTimer.Stage stage = timer.stage();
        String left = FireFreezeTimer.seconds(timer.remainingMs());

        List<String> lines = new ArrayList<>();
        lines.add("Fire Freeze  §7" + label(stage));
        lines.add(switch (stage) {
            case CAST -> "§7Cast in §f" + left;
            case WINDUP -> "§7Freezes in §f" + left;
            case FROZEN -> timer.remainingMs() > 0 ? "§7Frozen for §f" + left : "§7Frozen §8over";
            case DONE -> "";
        });

        int contentW = 0;
        for (String line : lines) {
            contentW = Math.max(contentW, font.width(strip(line)));
        }
        int width = Math.max(MIN_W, PAD * 2 + contentW);
        int lineH = font.lineHeight + LINE_GAP;
        int height = PAD * 2 + lineH * lines.size() - LINE_GAP;
        HudLayout.measure(HudElement.FIRE_FREEZE, x, y, width, height);

        HudCard.draw(g, x, y, width, height);

        int ix = x + PAD;
        int iy = y + PAD;
        g.text(font, Component.literal(lines.get(0)), ix, iy, SBSTheme.ACCENT_BRIGHT);
        iy += lineH;
        int color = switch (stage) {
            case CAST -> CAST_COLOR;
            case FROZEN -> FROZEN_COLOR;
            default -> SBSTheme.TEXT;
        };
        g.text(font, Component.literal(lines.get(1)), ix, iy, color);
    }

    private static String label(FireFreezeTimer.Stage stage) {
        return switch (stage) {
            case CAST -> "hold";
            case WINDUP -> "cast";
            case FROZEN -> "held";
            case DONE -> "";
        };
    }

    private static String strip(String text) {
        return text.replaceAll(SECTION_SIGN + ".", "");
    }
}
