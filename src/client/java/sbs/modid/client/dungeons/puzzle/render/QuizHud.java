/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.puzzle.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.dungeons.puzzle.logic.PuzzleCoordinator;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.Locale;

/**
 * The Quiz card: the countdown to Oruo's next question, on its own movable card rather than as a
 * label in the room. Only on screen while a countdown runs.
 */
public final class QuizHud {

    private static final int PAD = 6;
    private static final int MIN_W = 100;

    private QuizHud() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        if (Minecraft.getInstance().player == null || HudLayout.isHidden(HudElement.QUIZ_TIMER)) {
            return;
        }
        long remaining = PuzzleCoordinator.getInstance().quizNextQuestionMs();
        if (remaining <= 0) {
            return;
        }
        HudElement.Bounds bounds = HudElement.QUIZ_TIMER.defaultBounds(g.guiWidth(), g.guiHeight());
        HudLayout.begin(g, HudElement.QUIZ_TIMER);
        draw(g, remaining, (int) bounds.x(), (int) bounds.y());
        HudLayout.end(g);
    }

    private static void draw(GuiGraphicsExtractor g, long remaining, int x, int y) {
        Font font = Minecraft.getInstance().font;
        String seconds = String.format(Locale.ROOT, "%.1f", remaining / 1000.0) + "s";
        String text = "Next question §e" + seconds;
        int width = Math.max(MIN_W, PAD * 2 + font.width("Next question " + seconds));
        int height = PAD * 2 + font.lineHeight;
        HudLayout.measure(HudElement.QUIZ_TIMER, x, y, width, height);

        HudCard.draw(g, x, y, width, height);
        g.text(font, Component.literal(text), x + PAD, y + PAD, SBSTheme.TEXT);
    }
}
