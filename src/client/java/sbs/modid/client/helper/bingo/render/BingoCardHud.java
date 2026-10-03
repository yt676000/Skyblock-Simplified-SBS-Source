/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.bingo.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.helper.bingo.logic.BingoProfile;
import sbs.modid.client.helper.bingo.logic.BingoStore;
import sbs.modid.client.helper.bingo.model.BingoCard;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.render.RowText;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * The Bingo card on the HUD: open personal goals, then community goals with their progress line, then
 * (optionally) completed goals struck through, under a header saying how old the read is.
 *
 * <p>Only drawn on a Bingo profile ({@link BingoProfile}) with a card from the running event. The
 * width is capped from the screen and each line cut with {@link RowText#fit}; the number of goal lines
 * comes from the screen height, and the rest is summed up as "+N more" - a card of 25 long goal names
 * must not become a wall down one side of a 720p screen.
 */
public final class BingoCardHud {

    private static final int PAD = 5;
    private static final int LINE_GAP = 2;
    private static final int MIN_W = 110;
    private static final int PREFERRED_MAX_W = 230;
    private static final int MAX_GOAL_LINES = 12;
    /** After this long the header turns amber: the card only changes when the menu is opened. */
    private static final long STALE_MS = 12 * 3_600_000L;
    private static final int STALE_COLOR = 0xFFFFC85C;

    private BingoCardHud() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.BingoSettings cfg = ConfigManager.getInstance().get().bingo;
        if (!cfg.enabled || Minecraft.getInstance().player == null
                || HudLayout.isHidden(HudElement.BINGO_CARD) || !BingoProfile.getInstance().onBingo()) {
            return;
        }
        BingoCard card = BingoStore.getInstance().card();
        long now = System.currentTimeMillis();
        if (card == null || !card.current(now)) {
            return;   // never read on this profile, or last month's event
        }
        HudElement.Bounds bounds = HudElement.BINGO_CARD.defaultBounds(g.guiWidth(), g.guiHeight());
        HudLayout.begin(g, HudElement.BINGO_CARD);
        draw(g, cfg, card, now, (int) bounds.x(), (int) bounds.y());
        HudLayout.end(g);
    }

    private static void draw(GuiGraphicsExtractor g, SBSConfig.BingoSettings cfg, BingoCard card, long now,
                             int x, int y) {
        Font font = Minecraft.getInstance().font;
        int lineH = font.lineHeight + LINE_GAP;
        int maxW = Math.max(MIN_W, Math.min(PREFERRED_MAX_W, g.guiWidth() / 3));
        int textW = maxW - PAD * 2;
        int goalLines = Math.max(3, Math.min(MAX_GOAL_LINES, (g.guiHeight() / 2) / lineH - 2));

        List<String> lines = new ArrayList<>();
        for (BingoCard.Goal goal : card.goals) {
            if (!goal.done && !goal.community) {
                lines.add("§f" + goal.name);
            }
        }
        if (cfg.showCommunity) {
            for (BingoCard.Goal goal : card.goals) {
                if (!goal.done && goal.community) {
                    lines.add("§bCommunity: §f" + goal.name
                            + (goal.progress == null ? "" : "  §7" + goal.progress));
                }
            }
        }
        if (cfg.showCompleted) {
            for (BingoCard.Goal goal : card.goals) {
                if (goal.done && (!goal.community || cfg.showCommunity)) {
                    lines.add("§8§m" + goal.name);
                }
            }
        }
        if (lines.isEmpty()) {
            lines.add("§aEvery goal on the card is done");
        }
        int hidden = Math.max(0, lines.size() - goalLines);
        if (hidden > 0) {
            lines = new ArrayList<>(lines.subList(0, goalLines));
            lines.add("§7+" + hidden + " more");
        }

        long age = Math.max(0, now - card.capturedAt);
        String header = "Bingo - card from " + ago(age);
        int contentW = font.width(header);
        List<String> fitted = new ArrayList<>(lines.size());
        for (String line : lines) {
            String cut = RowText.fit(font, line, textW);
            fitted.add(cut);
            contentW = Math.max(contentW, font.width(cut));
        }
        int width = Math.max(MIN_W, Math.min(maxW, PAD * 2 + contentW));
        int height = PAD * 2 + lineH * (fitted.size() + 1) - LINE_GAP;
        HudLayout.measure(HudElement.BINGO_CARD, x, y, width, height);

        HudCard.draw(g, x, y, width, height);
        int ix = x + PAD;
        int iy = y + PAD;
        g.text(font, Component.literal(RowText.fit(font, header, textW)), ix, iy,
                age > STALE_MS ? STALE_COLOR : SBSTheme.ACCENT_BRIGHT);
        for (String line : fitted) {
            iy += lineH;
            g.text(font, Component.literal(line), ix, iy, SBSTheme.TEXT);
        }
    }

    /** "5m ago" / "3h ago" / "2d ago". */
    static String ago(long millis) {
        long minutes = millis / 60_000L;
        if (minutes < 60) {
            return Math.max(0, minutes) + "m ago";
        }
        long hours = minutes / 60;
        return hours < 48 ? hours + "h ago" : (hours / 24) + "d ago";
    }
}
