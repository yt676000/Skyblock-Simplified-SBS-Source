/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.dragonprofit.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import sbs.modid.client.combat.dragonprofit.logic.DragonFightLedger;
import sbs.modid.client.combat.dragonprofit.logic.DragonProfitTracker;
import sbs.modid.client.combat.dragonprofit.logic.DragonStats;
import sbs.modid.client.combat.dragonprofit.model.DragonType;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.ui.HudEditorScreen;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Dragon Profit card: the last fight's line, the running fight's eyes, and the session's (or
 * all-time) fights, eyes, profit, profit per fight, average position, Superiors and best drop,
 * optionally per dragon type. Fights are priced when they finish, so the snapshot depends only on
 * the tracker version and the settings.
 */
public final class DragonProfitHud {

    private static final int PAD = 5;
    private static final int LINE_GAP = 2;
    private static final int MIN_W = 140;
    private static final int COLUMN_GAP = 12;
    private static final int GAIN = 0xFF57D977;
    private static final int LOSS = 0xFFFF6060;

    private record Version(int tracker, boolean allTime, boolean perType) {
    }

    private static volatile HudCard.Snapshot snapshot;

    /** The sidebar zone the fights happen in; the card shows only there. */
    static final String NEST = "Dragon's Nest";

    private DragonProfitHud() {
    }

    /** Drawn from the HUD pass; self-gating. */
    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.DragonProfitSettings cfg = ConfigManager.getInstance().get().dragonProfit;
        if (!cfg.enabled || HudLayout.isHidden(HudElement.DRAGON_PROFIT) || Minecraft.getInstance().player == null) {
            return;
        }
        boolean editor = GuiStateManager.getInstance().getCurrentScreen() instanceof HudEditorScreen;
        // Only in the Dragon's Nest (asked 2026-10-17), not all over The End.
        if (!editor && !cfg.showEverywhere && !(SkyBlockLocation.onIsland("The End")
                && NEST.equalsIgnoreCase(SkyBlockLocation.zone()))) {
            return;
        }
        Version version = new Version(DragonProfitTracker.getInstance().version(), cfg.showAllTime, cfg.showPerType);
        HudCard.Snapshot card = snapshot;
        if (card == null || !card.isFor(version)) {
            card = build(version);
            snapshot = card;
        }
        HudElement.Bounds b = HudElement.DRAGON_PROFIT.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(HudElement.DRAGON_PROFIT, x, y, card.width(), card.height());
        HudLayout.begin(g, HudElement.DRAGON_PROFIT);
        HudCard.draw(g, x, y, card.width(), card.height());
        Font font = Minecraft.getInstance().font;
        int lineH = font.lineHeight + LINE_GAP;
        int iy = y + PAD;
        for (HudCard.Line line : card.rows()) {
            HudCard.drawLine(g, font, line, x + PAD, x + card.width() - PAD, iy);
            iy += lineH;
        }
        HudLayout.end(g);
    }

    private static HudCard.Snapshot build(Version v) {
        Font font = Minecraft.getInstance().font;
        DragonProfitTracker tracker = DragonProfitTracker.getInstance();
        DragonStats stats = v.allTime() ? tracker.allTime() : tracker.session();
        List<HudCard.Line> lines = new ArrayList<>();
        lines.add(HudCard.Line.of(font, v.allTime() ? "Dragon Profit (all time)" : "Dragon Profit",
                SBSTheme.ACCENT_BRIGHT));
        if (tracker.currentEyes() > 0) {
            lines.add(HudCard.Line.of(font, "This fight", SBSTheme.TEXT_MUTED,
                    tracker.currentEyes() + " eye" + (tracker.currentEyes() == 1 ? "" : "s"), SBSTheme.TEXT));
        }
        DragonFightLedger.Result last = stats.last();
        if (last != null) {
            lines.add(HudCard.Line.of(font, lastLine(last), SBSTheme.TEXT));
        }
        DragonStats.Totals t = stats.total();
        if (t.fights == 0) {
            lines.add(HudCard.Line.of(font, "No dragon fights yet", SBSTheme.TEXT_MUTED));
        } else {
            lines.add(HudCard.Line.of(font, "Fights", SBSTheme.TEXT_MUTED, String.valueOf(t.fights), SBSTheme.TEXT));
            lines.add(HudCard.Line.of(font, "Eyes used", SBSTheme.TEXT_MUTED, String.valueOf(t.eyes), SBSTheme.TEXT));
            lines.add(HudCard.Line.of(font, "Profit", SBSTheme.TEXT_MUTED, signed(t.profit()), colour(t.profit())));
            lines.add(HudCard.Line.of(font, "Per fight", SBSTheme.TEXT_MUTED, signed(t.profitPerFight()),
                    colour(t.profitPerFight())));
            if (t.averagePosition() > 0) {
                lines.add(HudCard.Line.of(font, "Avg position", SBSTheme.TEXT_MUTED,
                        String.format(Locale.ROOT, "#%.1f", t.averagePosition()), SBSTheme.TEXT));
            }
            if (t.superior > 0) {
                lines.add(HudCard.Line.of(font, "Superior", SBSTheme.TEXT_MUTED, String.valueOf(t.superior), GAIN));
            }
            if (t.bestDropId != null) {
                lines.add(HudCard.Line.of(font, "Best drop", SBSTheme.TEXT_MUTED,
                        pretty(t.bestDropId) + " " + NumberDisplay.format(t.bestDropValue), GAIN));
            }
            if (v.perType()) {
                for (DragonType type : DragonType.values()) {
                    DragonStats.Totals tt = stats.get(type);
                    if (tt != null && tt.fights > 0) {
                        lines.add(HudCard.Line.of(font, type.label() + " ×" + tt.fights, SBSTheme.TEXT_MUTED,
                                signed(tt.profit()), colour(tt.profit())));
                    }
                }
            }
        }
        int contentW = 0;
        for (HudCard.Line line : lines) {
            contentW = Math.max(contentW, line.width(COLUMN_GAP));
        }
        int lineH = font.lineHeight + LINE_GAP;
        int width = Math.max(MIN_W, contentW + PAD * 2);
        int height = PAD * 2 + lineH * lines.size() - LINE_GAP;
        return new HudCard.Snapshot(List.copyOf(lines), width, height, v);
    }

    /** "Wise · #4 · 1.2M dmg · +840k". */
    static String lastLine(DragonFightLedger.Result r) {
        StringBuilder sb = new StringBuilder(r.type() == null ? "Dragon" : r.type().label());
        if (r.position() > 0) {
            sb.append(" · #").append(r.position());
        }
        if (r.damage() >= 0) {
            sb.append(" · ").append(NumberDisplay.format(r.damage())).append(" dmg");
        }
        return sb.append(" · ").append(signed(r.profit())).toString();
    }

    private static String signed(long value) {
        return (value > 0 ? "+" : "") + NumberDisplay.format(value);
    }

    private static int colour(long value) {
        return value >= 0 ? GAIN : LOSS;
    }

    /** "WISE_FRAGMENT" to "Wise Fragment". */
    private static String pretty(String id) {
        StringBuilder sb = new StringBuilder();
        for (String word : id.toLowerCase(Locale.ROOT).split("_")) {
            if (!word.isEmpty()) {
                if (!sb.isEmpty()) {
                    sb.append(' ');
                }
                sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return sb.toString();
    }
}
