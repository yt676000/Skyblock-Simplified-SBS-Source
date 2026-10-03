/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.foraging.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.skills.foraging.logic.HoneyTreeTimers;
import sbs.modid.client.skills.foraging.model.HoneyTimer;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.List;

/**
 * The Honey Timers card: every running honey tree cooldown, readiest first.
 *
 * <p><b>Ready trees sort to the top rather than being dropped.</b> "Which trees are ready again" is
 * the question the whole feature exists to answer, so a finished timer is the most useful row on
 * the card, not a spent one.
 *
 * <p><b>Self-hiding.</b> With no timers there is no card at all - not an empty frame - which is
 * what the request asks for and what keeps it off the screen of everyone not currently doing honey.
 *
 * <p>The card measures itself from its rows, so {@link HudElement#HONEY_TIMERS}'s default bounds are
 * only the anchor the editor moves and scales about; {@code HudLayout.begin}/{@code end} is what
 * makes it movable, scalable and themable like every other element.
 */
public final class HoneyTimerHud {

    /** Width steps the card snaps to, so a countdown going 10:00 -> 9:59 does not twitch it. */
    private static final int WIDTH_STEP = 8;

    /** Minimum card width, so a one-row card is still a card. */
    private static final int MIN_WIDTH = 108;

    /** As many rows as fit before the card becomes a wall; the readiest are the ones that matter. */
    private static final int MAX_ROWS = 12;

    /** Running: amber. Ready: green. Unconfirmed or foreign: muted, and marked. */
    private static final int RUNNING = 0xFFFFD65A;
    private static final int READY = 0xFF57D977;

    private HoneyTimerHud() {
    }

    private static SBSConfig.HoneySettings cfg() {
        return ConfigManager.getInstance().get().honey;
    }

    /** Drawn from the HUD pass; self-hiding when there is nothing to say. */
    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.HoneySettings cfg = cfg();
        if (!cfg.enabled || !cfg.showHud || HudLayout.isHidden(HudElement.HONEY_TIMERS)) {
            return;
        }
        List<HoneyTimer> timers = HoneyTreeTimers.getInstance().visible();
        if (timers.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        int rows = Math.min(MAX_ROWS, timers.size());

        Font font = Minecraft.getInstance().font;
        int lineH = font.lineHeight + 2;
        int pad = 5;
        String header = "Honey Timers";
        int contentW = font.width(header);
        String[][] cells = new String[rows][2];
        int[] colours = new int[rows];
        for (int i = 0; i < rows; i++) {
            HoneyTimer timer = timers.get(i);
            String name = HoneyTreeTimers.displayName(timer);
            String where = timer.island == null ? "" : timer.island;
            String left = where.isEmpty() ? name : shortIsland(where) + " · " + name;
            boolean foreign = HoneyTreeTimers.fromAnotherServer(timer);
            String right = HoneyTimer.clock(timer.remainingMs(now));
            if (!timer.confirmed) {
                right += "?";
            }
            if (foreign) {
                right += "*";
            }
            cells[i][0] = left;
            cells[i][1] = right;
            colours[i] = timer.confirmed && !foreign
                    ? (timer.ready(now) ? READY : RUNNING)
                    : SBSTheme.TEXT_MUTED;
            contentW = Math.max(contentW, font.width(left) + 12 + font.width(right));
        }
        int width = quantise(Math.max(MIN_WIDTH, contentW + pad * 2));
        int height = pad * 2 + lineH * (1 + rows) - 2;

        HudElement.Bounds b = HudElement.HONEY_TIMERS.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(HudElement.HONEY_TIMERS, x, y, width, height);

        HudLayout.begin(g, HudElement.HONEY_TIMERS);
        HudCard.draw(g, x, y, width, height);

        int ix = x + pad;
        int right = x + width - pad;
        int iy = y + pad;
        g.text(font, Component.literal(header), ix, iy, SBSTheme.ACCENT_BRIGHT);
        iy += lineH;
        for (int i = 0; i < rows; i++) {
            g.text(font, Component.literal(cells[i][0]), ix, iy, SBSTheme.TEXT_MUTED);
            g.text(font, Component.literal(cells[i][1]),
                    right - font.width(cells[i][1]), iy, colours[i]);
            iy += lineH;
        }
        HudLayout.end(g);
    }

    /**
     * "Moonglade Marsh" -> "Moonglade".
     *
     * <p>The first word is enough to tell the two honey islands apart, and the row is a glance
     * rather than a sentence. Never used as an identity - the island in the key stays the full name
     * {@code SkyBlockLocation} reported.
     */
    private static String shortIsland(String island) {
        int space = island.indexOf(' ');
        return space <= 0 ? island : island.substring(0, space);
    }

    /** The widest the card has been recently, and when that was last raised. */
    private static int highWaterWidth;
    private static long highWaterAt;

    /** How long the card holds its widest measurement before it is allowed to shrink. */
    private static final long SHRINK_HOLD_MS = 1_500L;

    /**
     * Rounds the measured width up to a step and holds the widest recent value.
     *
     * <p>A countdown changes every second and a row appearing or leaving changes the widest string,
     * so without this the frame would twitch constantly - worst under the outline-only style, where
     * the ring is the whole shape and has nothing to hide behind.
     */
    private static int quantise(int width) {
        int stepped = ((width + WIDTH_STEP - 1) / WIDTH_STEP) * WIDTH_STEP;
        long now = System.currentTimeMillis();
        if (stepped >= highWaterWidth) {
            highWaterWidth = stepped;
            highWaterAt = now;
            return stepped;
        }
        if (now - highWaterAt < SHRINK_HOLD_MS) {
            return highWaterWidth;
        }
        highWaterWidth = stepped;
        highWaterAt = now;
        return stepped;
    }
}
