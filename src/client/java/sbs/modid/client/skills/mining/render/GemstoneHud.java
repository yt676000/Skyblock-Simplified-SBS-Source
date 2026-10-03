/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig.GemstoneProfitSettings;
import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.skills.SkillIslands;
import sbs.modid.client.skills.mining.logic.GemstoneCatalog;
import sbs.modid.client.skills.mining.logic.GemstoneProfit;
import sbs.modid.client.skills.mining.logic.GemstoneTracker;
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
 * The gemstone profit card: what this session's gemstones are worth, what that projects to per hour,
 * and whether combining them first would be worth more.
 *
 * <p><b>Every projected figure says it is one.</b> A measured total and an hourly projection look the
 * same on a HUD unless something makes them different, and the projection is the one people act on -
 * so it carries the word, is drawn in its own colour, and the assumptions behind it (the tax rate,
 * the price age) are on the card rather than in a settings screen nobody has open while mining.
 *
 * <p><b>Coins only.</b> Powder has its own card. Blending the two into one "coins per hour" would
 * hide exactly the tradeoff a player is trying to make when they choose where to mine.
 */
public final class GemstoneHud {

    private static final int PAD = 6;
    private static final int LINE_GAP = 3;

    private GemstoneHud() {
    }

    private static GemstoneProfitSettings cfg() {
        return ConfigManager.getInstance().get().gemstoneProfit;
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        GemstoneProfitSettings settings = cfg();
        GemstoneTracker tracker = GemstoneTracker.getInstance();
        if (!settings.enabled || !settings.card || Minecraft.getInstance().player == null
                || HudLayout.isHidden(HudElement.GEMSTONE_PROFIT)) {
            return;
        }
        // The island-binding rule: visible where its content applies and nowhere else. The session is
        // NOT dropped on leaving - walking to the Bazaar to sell should not erase what you just mined.
        if (!SkillIslands.miningAllowed() || !tracker.hasData()) {
            return;
        }
        HudElement.Bounds bounds =
                HudElement.GEMSTONE_PROFIT.defaultBounds(g.guiWidth(), g.guiHeight());
        HudLayout.begin(g, HudElement.GEMSTONE_PROFIT);
        draw(g, tracker, settings, (int) bounds.x(), (int) bounds.y());
        HudLayout.end(g);
    }

    private static void draw(GuiGraphicsExtractor g, GemstoneTracker tracker,
                             GemstoneProfitSettings settings, int x, int y) {
        Font font = Minecraft.getInstance().font;
        String title = "Gemstones";
        List<Row> rows = new ArrayList<>();

        boolean priced = tracker.anyPriced();
        if (!priced) {
            // A missing price is said out loud rather than shown as a zero: zero coins is a claim,
            // and "we do not have the prices" is the truth.
            rows.add(new Row("Value", "no Bazaar prices yet", SBSTheme.TEXT_MUTED));
        } else {
            rows.add(new Row("Sold as mined", NumberDisplay.format(Math.round(tracker.netValue())),
                    settings.totalColor));
            if (settings.perHour) {
                rows.add(rateRow(tracker, settings));
            }
        }

        if (settings.perGemstone) {
            addPerGemstoneRows(rows, tracker);
        }
        if (settings.combineAdvice && priced) {
            addCombineRow(rows, tracker, settings);
        }
        if (settings.volumeWarning && priced) {
            addVolumeRow(rows, tracker, settings);
        }
        rows.add(assumptionsRow(settings));

        // ---- measure against the real content, then draw
        int lineH = font.lineHeight + LINE_GAP;
        int contentW = font.width(title);
        for (Row row : rows) {
            contentW = Math.max(contentW, font.width(row.label) + 12 + font.width(row.value));
        }
        int width = Math.max(150, PAD * 2 + contentW);
        int height = PAD * 2 + lineH * (1 + rows.size()) - LINE_GAP;
        HudLayout.measure(HudElement.GEMSTONE_PROFIT, x, y, width, height);
        panel(g, x, y, width, height);

        int ix = x + PAD;
        int right = x + width - PAD;
        int iy = y + PAD;
        g.text(font, Component.literal(title), ix, iy, SBSTheme.ACCENT_BRIGHT);
        iy += lineH;
        for (Row row : rows) {
            g.text(font, Component.literal(row.label), ix, iy, SBSTheme.TEXT_MUTED);
            g.text(font, Component.literal(row.value), right - font.width(row.value), iy, row.color);
            iy += lineH;
        }
    }

    private static Row rateRow(GemstoneTracker tracker, GemstoneProfitSettings settings) {
        if (!tracker.rateReady()) {
            // Named as a wait rather than shown as 0/h, which would read as "this spot earns nothing".
            long left = (60_000L - tracker.elapsedMs()) / 1000L;
            return new Row("Projected /h", "measuring… " + Math.max(1, left) + "s",
                    SBSTheme.TEXT_MUTED);
        }
        return new Row("Projected /h",
                NumberDisplay.format(Math.round(tracker.netPerHour())) + " (projection)",
                settings.projectionColor);
    }

    private static void addPerGemstoneRows(List<Row> rows, GemstoneTracker tracker) {
        for (Map.Entry<GemstoneCatalog.Gem, Long> entry : tracker.counts().entrySet()) {
            GemstoneCatalog.Gem gem = entry.getKey();
            double unit = GemstoneProfit.netUnitValue(gem);
            String value = unit < 0
                    ? entry.getValue() + "  (unpriced)"
                    : entry.getValue() + "  " + NumberDisplay.format(Math.round(unit * entry.getValue()));
            rows.add(new Row(gem.shortName(), value, gem.type().color()));
        }
    }

    /**
     * The single best rung of the ladder, when it beats selling as mined by enough to be worth the
     * clicks. The certainty of the ratio behind it is shown - an unverified recommendation should not
     * look like a measured one.
     */
    private static void addCombineRow(List<Row> rows, GemstoneTracker tracker,
                                      GemstoneProfitSettings settings) {
        GemstoneCatalog.Gem bestGem = null;
        GemstoneProfit.Rung bestRung = null;
        double bestGainRatio = 1.0;
        for (Map.Entry<GemstoneCatalog.Gem, Long> entry : tracker.counts().entrySet()) {
            List<GemstoneProfit.Rung> ladder = GemstoneProfit.ladder(entry.getKey(), entry.getValue());
            if (ladder.isEmpty() || !ladder.get(0).priced()) {
                continue;
            }
            GemstoneProfit.Rung asMined = ladder.get(0);
            GemstoneProfit.Rung best = GemstoneProfit.best(ladder);
            if (best == null || best.tier() == asMined.tier() || asMined.netValue() <= 0) {
                continue;
            }
            double ratio = best.netValue() / asMined.netValue();
            if (ratio > bestGainRatio) {
                bestGainRatio = ratio;
                bestRung = best;
                bestGem = entry.getKey();
            }
        }
        if (bestRung == null || bestGem == null) {
            rows.add(new Row("Combine?", "sell as mined", SBSTheme.TEXT_MUTED));
            return;
        }
        String gain = String.format(Locale.ROOT, "+%.0f%%", (bestGainRatio - 1.0) * 100.0);
        rows.add(new Row("Combine to " + bestRung.tier().displayName(),
                gain + " " + bestRung.certainty().colorCode() + "(" + bestRung.certainty().displayName() + ")",
                settings.projectionColor));
    }

    /**
     * Whether the projected hourly output is large against what the product actually trades. Only the
     * worst offender is shown - a card is not a report, and the point is to notice at all.
     */
    private static void addVolumeRow(List<Row> rows, GemstoneTracker tracker,
                                     GemstoneProfitSettings settings) {
        if (!tracker.rateReady()) {
            return;
        }
        GemstoneCatalog.Gem worstGem = null;
        GemstoneProfit.VolumeCheck worst = null;
        for (GemstoneCatalog.Gem gem : tracker.counts().keySet()) {
            GemstoneProfit.VolumeCheck check =
                    GemstoneProfit.volumeCheck(gem, tracker.unitsPerHour(gem));
            if (check != null && check.heavy() && (worst == null || check.share() > worst.share())) {
                worst = check;
                worstGem = gem;
            }
        }
        if (worst == null || worstGem == null) {
            return;
        }
        rows.add(new Row("Thin market", worstGem.shortName() + " "
                + String.format(Locale.ROOT, "%.0f%% of /h volume", worst.share() * 100.0),
                settings.warningColor));
    }

    /** The assumptions the numbers above rest on, on the card rather than buried in settings. */
    private static Row assumptionsRow(GemstoneProfitSettings settings) {
        long ageSeconds = GemstoneProfit.priceAgeMs() / 1000L;
        String age = ageSeconds > 86_400 ? "never" : ageSeconds + "s ago";
        return new Row("§8after " + String.format(Locale.ROOT, "%.3f%%", GemstoneProfit.taxPercent())
                + " tax", "§8prices " + age, SBSTheme.TEXT_MUTED);
    }

    private record Row(String label, String value, int color) {
    }

    private static void panel(GuiGraphicsExtractor g, int x, int y, int width, int height) {
        HudCard.draw(g, x, y, width, height);
    }
}
