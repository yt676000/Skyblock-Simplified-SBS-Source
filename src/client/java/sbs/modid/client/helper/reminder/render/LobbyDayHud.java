/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.reminder.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.helper.reminder.logic.HollowsLobbyWatch;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.theme.SBSTheme;

/**
 * The "Lobby Day" card on the Crystal Hollows: {@code Day 17.4 · closes in ~32 min}.
 *
 * <p>The text is worked out once a second by {@link HollowsLobbyWatch}; this only picks a colour
 * and draws. The colour follows the lead window: normal before it, yellow shading to orange through
 * it, red from the cutoff day on, muted while the clock is unknown.
 */
public final class LobbyDayHud {

    private static final int PAD = 5;
    private static final int YELLOW = 0xFFFFD65A;
    private static final int ORANGE = 0xFFFF9A40;
    private static final int RED = 0xFFFF6060;

    private LobbyDayHud() {
    }

    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.RemindersSettings cfg = ConfigManager.getInstance().get().reminders;
        if (!cfg.enabled || !cfg.hollowsLobbyClosing || !cfg.hollowsHud
                || HudLayout.isHidden(HudElement.LOBBY_DAY)) {
            return;
        }
        HollowsLobbyWatch watch = HollowsLobbyWatch.getInstance();
        String text = watch.cardText();
        if (!watch.inHollows() || text.isEmpty()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        int width = font.width(text) + PAD * 2;
        int height = font.lineHeight + PAD * 2 - 2;
        HudElement.Bounds b = HudElement.LOBBY_DAY.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(HudElement.LOBBY_DAY, x, y, width, height);
        HudLayout.begin(g, HudElement.LOBBY_DAY);
        HudCard.draw(g, x, y, width, height);
        g.text(font, Component.literal(text), x + PAD, y + PAD, colour(watch));
        HudLayout.end(g);
    }

    private static int colour(HollowsLobbyWatch watch) {
        if (watch.clockTicks() < 0L) {
            return SBSTheme.TEXT_MUTED;
        }
        double p = watch.progress();
        if (p >= 1.0) {
            return RED;
        }
        if (p < 0.0) {
            return SBSTheme.TEXT;
        }
        return lerp(YELLOW, ORANGE, p);
    }

    /** Channel-wise blend of two opaque colours, {@code t} in [0, 1]. */
    private static int lerp(int from, int to, double t) {
        int r = (int) Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int gr = (int) Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int bl = (int) Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return 0xFF000000 | (r << 16) | (gr << 8) | bl;
    }
}
