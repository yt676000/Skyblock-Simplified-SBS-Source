/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.yearofthepig.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.helper.yearofthepig.logic.ShinyOrbTracker;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemCatalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Year of the Pig session card: orbs spent and delivered, the success rate, net profit (and per
 * hour / per orb), the Shiny Tokens earned, and the drop list with per-item values.
 *
 * <p>Self-hiding – it draws only while an orb is out or one was resolved recently, so it stays off
 * the screen entirely outside the event. Movable / scalable via the GUI editor.
 */
public final class ShinyPigHud {

    private static final int PAD = 6;
    private static final int LINE_GAP = 3;
    private static final int MAX_DROPS = 8;
    private static final int GAIN_COLOR = 0xFF57D977;
    private static final int LOSS_COLOR = 0xFFE05B5B;

    private ShinyPigHud() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        var cfg = ConfigManager.getInstance().get().yearOfThePig;
        ShinyOrbTracker tracker = ShinyOrbTracker.getInstance();
        if (!cfg.enabled || !cfg.showTracker || Minecraft.getInstance().player == null
                || !tracker.sessionVisible() || HudLayout.isHidden(HudElement.SHINY_PIG_TRACKER)) {
            return;
        }
        HudElement.Bounds bounds = HudElement.SHINY_PIG_TRACKER.defaultBounds(g.guiWidth(), g.guiHeight());
        HudLayout.begin(g, HudElement.SHINY_PIG_TRACKER);
        draw(g, tracker, (int) bounds.x(), (int) bounds.y());
        HudLayout.end(g);
    }

    private static void draw(GuiGraphicsExtractor g, ShinyOrbTracker tracker, int x, int y) {
        Font font = Minecraft.getInstance().font;

        List<String[]> rows = new ArrayList<>();   // [label, value]
        rows.add(new String[] {"Orbs used", String.valueOf(tracker.orbsUsed())});
        if (tracker.orbsCharged() > 0 || tracker.orbsExpired() > 0) {
            String expired = tracker.orbsExpired() > 0 ? "  (" + tracker.orbsExpired() + " lost)" : "";
            rows.add(new String[] {"Delivered", tracker.orbsCharged() + expired});
            rows.add(new String[] {"Success", String.format(Locale.US, "%.0f%%", tracker.successRate())});
        }
        if (tracker.shinyTokens() > 0) {
            rows.add(new String[] {"Shiny Tokens", String.valueOf(tracker.shinyTokens())});
        }
        if (tracker.coinsGained() > 0) {
            rows.add(new String[] {"Coins won", coins(tracker.coinsGained())});
        }
        // The orb cost is the whole reason a session can end in the red, so it is always shown once
        // anything has been spent - a "profit" line alone would hide where the money went.
        if (tracker.orbsUsed() > 0) {
            rows.add(new String[] {"Orb cost", "-" + coins(tracker.spent())});
        }

        double profit = tracker.profit();
        boolean anyProfit = tracker.orbsUsed() > 0 || profit != 0;
        String profitText = null;
        if (anyProfit) {
            String perHour = tracker.profitPerHour() != 0
                    ? "  (" + coins(tracker.profitPerHour()) + "/h)" : "";
            profitText = (profit < 0 ? "-" : "") + coins(Math.abs(profit)) + perHour;
            rows.add(new String[] {"Profit", profitText});
            if (tracker.orbsUsed() > 0) {
                double perOrb = tracker.profitPerOrb();
                rows.add(new String[] {"Per orb",
                        (perOrb < 0 ? "-" : "") + coins(Math.abs(perOrb))});
            }
        }

        // Drop list, most valuable first.
        List<String[]> dropRows = new ArrayList<>();
        tracker.loot().entrySet().stream()
                .sorted((a, b) -> Double.compare(
                        tracker.price(b.getKey()) * b.getValue(),
                        tracker.price(a.getKey()) * a.getValue()))
                .limit(MAX_DROPS)
                .forEach(entry -> dropRows.add(new String[] {
                        entry.getValue() + "x " + displayName(entry.getKey()),
                        coins(tracker.price(entry.getKey()) * entry.getValue())}));

        // Skill XP is income the loot table pays in experience, not coins - listed apart from the
        // priced drops so it never looks like it contributed to the profit number.
        List<String[]> xpRows = new ArrayList<>();
        for (Map.Entry<String, Long> entry : tracker.skillXp().entrySet()) {
            xpRows.add(new String[] {entry.getKey() + " XP", coins(entry.getValue())});
        }

        int lineH = font.lineHeight + LINE_GAP;
        String title = "Shiny Pigs";
        int contentW = font.width(title) + 10;
        for (String[] row : rows) {
            contentW = Math.max(contentW, font.width(row[0]) + 12 + font.width(row[1]));
        }
        for (String[] row : dropRows) {
            contentW = Math.max(contentW, font.width(row[0]) + 12 + font.width(row[1]));
        }
        for (String[] row : xpRows) {
            contentW = Math.max(contentW, font.width(row[0]) + 12 + font.width(row[1]));
        }
        int width = Math.max(150, PAD * 2 + contentW);
        int height = PAD * 2 + lineH * (1 + rows.size() + dropRows.size() + xpRows.size()) - LINE_GAP;
        HudLayout.measure(HudElement.SHINY_PIG_TRACKER, x, y, width, height);

        HudCard.draw(g, x, y, width, height);

        int ix = x + PAD;
        int right = x + width - PAD;
        int iy = y + PAD;

        g.text(font, Component.literal(title), ix, iy, SBSTheme.ACCENT_BRIGHT);
        iy += lineH;
        for (String[] row : rows) {
            boolean isProfit = row[0].equals("Profit") || row[0].equals("Per orb");
            boolean isCost = row[0].equals("Orb cost");
            int color = SBSTheme.TEXT;
            if (isCost || (isProfit && row[1].startsWith("-"))) {
                color = LOSS_COLOR;
            } else if (isProfit) {
                color = GAIN_COLOR;
            }
            g.text(font, Component.literal(row[0]), ix, iy, SBSTheme.TEXT_MUTED);
            g.text(font, Component.literal(row[1]), right - font.width(row[1]), iy, color);
            iy += lineH;
        }
        for (String[] row : dropRows) {
            g.text(font, Component.literal(row[0]), ix, iy, SBSTheme.TEXT);
            g.text(font, Component.literal(row[1]), right - font.width(row[1]), iy, GAIN_COLOR);
            iy += lineH;
        }
        for (String[] row : xpRows) {
            g.text(font, Component.literal(row[0]), ix, iy, SBSTheme.TEXT_MUTED);
            g.text(font, Component.literal(row[1]), right - font.width(row[1]), iy, SBSTheme.TEXT);
            iy += lineH;
        }
    }

    private static String displayName(String itemId) {
        SkyBlockItemCatalog.Entry entry = SkyBlockItemCatalog.getInstance().byId(itemId);
        if (entry != null && entry.name != null && !entry.name.isEmpty()) {
            return entry.name;
        }
        String[] words = itemId.toLowerCase(Locale.ROOT).split("_");
        StringBuilder pretty = new StringBuilder();
        for (String word : words) {
            if (!word.isEmpty()) {
                if (pretty.length() > 0) {
                    pretty.append(' ');
                }
                pretty.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return pretty.toString();
    }

    /** 12_400_000 -> "12.4M", 830_000 -> "830K", 950 -> "950" (unless "Shorten Numbers" is off). */
    private static String coins(double value) {
        return sbs.modid.client.core.util.NumberDisplay.format(value);
    }
}
