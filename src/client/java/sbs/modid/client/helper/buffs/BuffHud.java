/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.buffs;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * The God Potion and Booster Cookie cards. Two separate HUD elements rather than one combined card:
 * the two buffs run on wildly different scales (hours against months), so most players want the
 * expiring one where they will see it and the other one out of the way.
 *
 * <p>A card draws only while its buff is actually active - an empty "God Potion: none" card would
 * cost permanent screen space to say nothing.
 *
 * <p><b>Both are off by default.</b> The same two timers are rows in the Custom Scoreboard now
 * ({@code CustomScoreboardRenderer.addBuffRows}), which is where the eye already goes for status;
 * these cards remain for anyone who wants a timer somewhere of its own, at its own scale and opacity.
 */
public final class BuffHud {

    private static final int PAD = 5;

    private BuffHud() {
    }

    public static void render(GuiGraphicsExtractor g) {
        var cfg = ConfigManager.getInstance().get().buffs;
        if (!cfg.enabled || Minecraft.getInstance().player == null) {
            return;
        }
        BuffTracker tracker = BuffTracker.getInstance();
        if (cfg.godPotionCard) {
            card(g, HudElement.GOD_POT_TIMER, "God Potion", tracker.godPotion(),
                    BuffColors.godPotionName(), BuffColors.godPotionTime());
        }
        if (cfg.cookieCard) {
            card(g, HudElement.COOKIE_BUFF_TIMER, "Cookie Buff", tracker.cookieBuff(),
                    BuffColors.cookieBuffName(), BuffColors.cookieBuffTime());
        }
        if (cfg.cakeCard) {
            cakeCard(g, cfg.cakeCollapsed);
        }
    }

    /**
     * The Century Cake card: a "Cakes" header and one row per active buff ("+5 Mining Fortune" and
     * its time left), or a single summary line when collapsed. Draws only while a cake is active.
     */
    private static void cakeCard(GuiGraphicsExtractor g, boolean collapsed) {
        List<CakeBuffBook.Buff> cakes = CakeBuffStore.getInstance().active();
        if (cakes.isEmpty() || HudLayout.isHidden(HudElement.CAKE_BUFFS)) {
            return;
        }
        long now = System.currentTimeMillis();
        List<String[]> rows = new ArrayList<>();
        if (collapsed) {
            rows.add(new String[] {cakes.size() + (cakes.size() == 1 ? " cake" : " cakes")
                    + " - next ends in", CakeBuffBook.remaining(cakes.get(0).expiresAt, now)});
        } else {
            for (CakeBuffBook.Buff cake : cakes) {
                rows.add(new String[] {"+" + cake.amount + " " + cake.stat,
                        CakeBuffBook.remaining(cake.expiresAt, now)});
            }
        }
        Font font = Minecraft.getInstance().font;
        int lineH = font.lineHeight + 2;
        int contentW = font.width("Cakes");
        for (String[] row : rows) {
            contentW = Math.max(contentW, font.width(row[0]) + 8 + font.width(row[1]));
        }
        int width = Math.max(96, contentW + PAD * 2);
        int height = PAD * 2 + lineH * (1 + rows.size()) - 2;

        HudElement.Bounds b = HudElement.CAKE_BUFFS.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(HudElement.CAKE_BUFFS, x, y, width, height);

        HudLayout.begin(g, HudElement.CAKE_BUFFS);
        HudCard.draw(g, x, y, width, height);
        g.text(font, Component.literal("Cakes"), x + PAD, y + PAD, SBSTheme.ACCENT_BRIGHT);
        int iy = y + PAD + lineH;
        int right = x + width - PAD;
        for (String[] row : rows) {
            g.text(font, Component.literal(row[0]), x + PAD, iy, SBSTheme.TEXT);
            g.text(font, Component.literal(row[1]), right - font.width(row[1]), iy, SBSTheme.TEXT_MUTED);
            iy += lineH;
        }
        HudLayout.end(g);
    }

    /** One labelled card, sized to its text; skipped entirely when the buff is not active. */
    private static void card(GuiGraphicsExtractor g, HudElement element, String label,
                             String value, int nameRgb, int timeRgb) {
        if (value == null || HudLayout.isHidden(element)) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        int lineH = font.lineHeight + 2;
        int contentW = Math.max(font.width(label), font.width(value));
        int width = Math.max(96, contentW + PAD * 2);
        int height = PAD * 2 + lineH * 2 - 2;

        HudElement.Bounds b = element.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(element, x, y, width, height);

        HudLayout.begin(g, element);
        HudCard.draw(g, x, y, width, height);

        // The card's own two colours, not the theme's: the value used to be SBSTheme.TEXT, which
        // made the time on a card and the time on the scoreboard row two different colours for one
        // number. Both readouts are drawn from BuffColors now.
        g.text(font, Component.literal(label), x + PAD, y + PAD, 0xFF000000 | nameRgb);
        g.text(font, Component.literal(value), x + PAD, y + PAD + lineH, 0xFF000000 | timeRgb);
        HudLayout.end(g);
    }
}
