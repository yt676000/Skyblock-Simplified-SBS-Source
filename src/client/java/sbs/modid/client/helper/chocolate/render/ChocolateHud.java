/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.chocolate.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.economy.bazaar.ui.FlipFormat;
import sbs.modid.client.helper.chocolate.logic.ChocolateStore;
import sbs.modid.client.helper.chocolate.logic.UpgradeRanking;
import sbs.modid.client.helper.chocolate.model.ChocolateSnapshot;
import sbs.modid.client.helper.chocolate.model.FactoryUpgrade;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * The Chocolate Factory card: production, the best buy and what it is still waiting on, plus the
 * Time Tower and barn.
 *
 * <p><b>Everything on it is history, and it says so.</b> The menu is the only source this feature
 * has, so the moment it is shut nothing here is live. The last line is always the age of the
 * capture - a charge count drawn as though it were current is precisely the confident wrong number
 * root {@code AGENTS.md} forbids, and the fix is one line of text rather than a disclaimer
 * somewhere else.
 *
 * <p>Hidden entirely until something has been captured: a card reading "unknown" four times is
 * worse than no card.
 */
public final class ChocolateHud {

    private static final int PAD = 6;
    private static final int LINE_GAP = 2;
    private static final int MIN_W = 150;

    private static final int WARN_COLOR = 0xFFFFC85C;

    private ChocolateHud() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.ChocolateFactorySettings cfg = ConfigManager.getInstance().get().chocolateFactory;
        if (!cfg.enabled || !cfg.showHud || Minecraft.getInstance().player == null
                || HudLayout.isHidden(HudElement.CHOCOLATE_FACTORY)) {
            return;
        }
        ChocolateSnapshot snapshot = ChocolateStore.getInstance().snapshot();
        if (snapshot.empty()) {
            return;
        }
        HudElement.Bounds bounds =
                HudElement.CHOCOLATE_FACTORY.defaultBounds(g.guiWidth(), g.guiHeight());
        HudLayout.begin(g, HudElement.CHOCOLATE_FACTORY);
        draw(g, snapshot, cfg, (int) bounds.x(), (int) bounds.y());
        HudLayout.end(g);
    }

    private static void draw(GuiGraphicsExtractor g, ChocolateSnapshot snapshot,
                             SBSConfig.ChocolateFactorySettings cfg, int x, int y) {
        Font font = Minecraft.getInstance().font;
        List<String[]> rows = new ArrayList<>(6);

        if (snapshot.perSecond > 0) {
            rows.add(new String[] {"Per second", NumberDisplay.format(snapshot.perSecond)});
        }

        FactoryUpgrade best = UpgradeRanking.best(snapshot.upgrades);
        if (best != null) {
            rows.add(new String[] {"Best buy", best.name()});
            rows.add(new String[] {"Pays back in",
                    FlipFormat.duration((long) UpgradeRanking.paybackSeconds(best))});
            long missing = UpgradeRanking.shortfall(best, snapshot.balance);
            if (missing > 0) {
                double wait = UpgradeRanking.secondsToAfford(best, snapshot.balance, snapshot.perSecond);
                rows.add(new String[] {"Still needs", NumberDisplay.format(missing)
                        + (wait > 0 ? " (" + FlipFormat.duration((long) wait) + ")" : "")});
            }
        }
        if (snapshot.towerCharges >= 0) {
            rows.add(new String[] {"Time Tower",
                    snapshot.towerCharges + "/" + Math.max(0, snapshot.towerMaxCharges)
                            + (snapshot.towerActiveSeconds > 0
                            ? " - " + FlipFormat.duration(snapshot.towerActiveSeconds) + " left"
                            : "")});
        }
        if (snapshot.barnRabbits >= 0 && snapshot.barnCapacity > 0) {
            rows.add(new String[] {"Barn",
                    snapshot.barnRabbits + "/" + snapshot.barnCapacity});
        }
        if (rows.isEmpty()) {
            return; // captured, but nothing in it parsed - drawing an empty frame says nothing
        }

        String age = "as of " + age(snapshot.capturedAt);
        int contentW = font.width(age);
        for (String[] row : rows) {
            contentW = Math.max(contentW, font.width(row[0]) + 10 + font.width(row[1]));
        }
        int width = Math.max(MIN_W, PAD * 2 + contentW);
        int lineH = font.lineHeight + LINE_GAP;
        int height = PAD * 2 + lineH * (rows.size() + 2);
        // Measured from what is actually drawn, so the editor's drag box matches the card.
        HudLayout.measure(HudElement.CHOCOLATE_FACTORY, x, y, width, height);

        HudCard.draw(g, x, y, width, height);
        int iy = y + PAD;
        g.text(font, Component.literal("Chocolate Factory"), x + PAD, iy, SBSTheme.ACCENT);
        iy += lineH;
        for (String[] row : rows) {
            g.text(font, Component.literal(row[0]), x + PAD, iy, SBSTheme.TEXT_MUTED);
            int valueW = font.width(row[1]);
            boolean warn = cfg.barnWarning && "Barn".equals(row[0])
                    && snapshot.barnFull(cfg.barnWarnPercent);
            g.text(font, Component.literal(row[1]), x + width - PAD - valueW, iy,
                    warn ? WARN_COLOR : SBSTheme.TEXT);
            iy += lineH;
        }
        g.text(font, Component.literal(age), x + PAD, iy, SBSTheme.TEXT_MUTED);
    }

    /** "just now" / "12 min ago" / "3 h ago" - never absent, so nothing reads as live. */
    private static String age(long capturedAt) {
        long minutes = (System.currentTimeMillis() - capturedAt) / 60_000L;
        if (minutes < 1) {
            return "just now";
        }
        return minutes < 60 ? minutes + " min ago" : (minutes / 60) + " h ago";
    }
}
