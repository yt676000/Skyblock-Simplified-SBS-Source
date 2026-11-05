/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.farming.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.render.CachedModel;
import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.skills.SkillIslands;
import sbs.modid.client.skills.farming.model.CropType;
import sbs.modid.client.skills.farming.net.CropPriceSource;
import sbs.modid.client.skills.farming.net.FarmingNetLedger;
import sbs.modid.client.skills.farming.net.FarmingNetTracker;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Farming Net card: Crops, Pests, Visitors, Costs, Net and Net per active hour, optionally with a
 * line per crop. A "+" after a figure means part of it has no price. Farming islands only, once the
 * ledger holds anything.
 */
public final class FarmingNetHud {

    private static final int PAD = 5;
    private static final int MIN_WIDTH = 140;
    private static final int GAIN = 0xFF57D977;
    private static final int LOSS = 0xFFFF6060;

    /**
     * Everything the card's text depends on: the ledger's revision, the pricing settings, the
     * breakdown switch, and the one-second clock (prices move and the extra hourly cost grows).
     */
    private record Key(int revision, long second, CropPriceSource source, int coinsPerCopper,
                       boolean visitors, int extraPerHour, boolean breakdown, boolean traps) {
    }

    private static final CachedModel<Key, HudCard.Snapshot> MODEL =
            new CachedModel<>("farmingNet", FarmingNetHud::build);

    private FarmingNetHud() {
    }

    /** Drawn from the HUD pass; self-hiding off the farming islands and while nothing is counted. */
    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.GardenHelpersSettings cfg = ConfigManager.getInstance().get().gardenHelpers;
        if (!cfg.farmingNet || HudLayout.isHidden(HudElement.FARMING_NET)
                || Minecraft.getInstance().player == null || !SkillIslands.onFarmingIsland()) {
            return;
        }
        FarmingNetLedger ledger = FarmingNetTracker.getInstance().ledger();
        if (ledger.isEmpty()) {
            return;
        }
        HudCard.Snapshot card = MODEL.get(new Key(ledger.revision(), System.currentTimeMillis() / 1000L,
                cfg.farmingNetPriceSource, cfg.farmingNetCoinsPerCopper, cfg.farmingNetVisitors,
                cfg.farmingNetExtraCostPerHour, cfg.farmingNetBreakdown, cfg.pestProfitTrapLoot));

        Font font = Minecraft.getInstance().font;
        int lineH = font.lineHeight + 2;
        HudElement.Bounds b = HudElement.FARMING_NET.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(HudElement.FARMING_NET, x, y, card.width(), card.height());

        HudLayout.begin(g, HudElement.FARMING_NET);
        HudCard.draw(g, x, y, card.width(), card.height());
        int ix = x + PAD;
        int right = x + card.width() - PAD;
        int iy = y + PAD;
        for (HudCard.Line line : card.rows()) {
            HudCard.drawLine(g, font, line, ix, right, iy);
            iy += lineH;
        }
        HudLayout.end(g);
    }

    private static HudCard.Snapshot build(Key key) {
        FarmingNetLedger.Totals t = FarmingNetTracker.getInstance().ledger().totals(FarmingNetTracker.pricing());
        String mark = t.unpriced() ? "+" : "";

        List<Row> rows = new ArrayList<>();
        rows.add(new Row("Crops", NumberDisplay.format(t.crops()), GAIN));
        if (key.breakdown()) {
            for (Map.Entry<String, Long> e : t.cropCoins().entrySet()) {
                rows.add(new Row("  " + cropName(e.getKey()), NumberDisplay.format(e.getValue()), SBSTheme.TEXT_MUTED));
            }
        }
        rows.add(new Row("Pests", NumberDisplay.format(t.pests()), GAIN));
        if (key.visitors()) {
            rows.add(new Row("Visitors (" + t.visitorCount() + ")", signed(t.visitorNet()), colour(t.visitorNet())));
            if (key.breakdown()) {
                rows.add(new Row("  + " + NumberDisplay.format(t.copper()) + " copper",
                        NumberDisplay.format(t.visitorRewards()), SBSTheme.TEXT_MUTED));
                rows.add(new Row("  - items given", NumberDisplay.format(t.visitorItems()), SBSTheme.TEXT_MUTED));
            }
        }
        rows.add(new Row("Costs", t.costs() == 0 ? "0" : "-" + NumberDisplay.format(t.costs()), LOSS));
        if (key.breakdown() && t.consumables() > 0) {
            rows.add(new Row("  - consumables", NumberDisplay.format(t.consumables()), SBSTheme.TEXT_MUTED));
        }
        rows.add(new Row("Net", signed(t.net()) + mark, colour(t.net())));
        rows.add(new Row("Net / h", t.perHour() == 0 && t.activeMs() < 60_000L ? "-" : signed(t.perHour()) + mark,
                colour(t.perHour())));

        Font font = Minecraft.getInstance().font;
        int lineH = font.lineHeight + 2;
        List<HudCard.Line> lines = new ArrayList<>(rows.size() + 1);
        lines.add(HudCard.Line.of(font, "Farming Net", SBSTheme.ACCENT_BRIGHT));
        int contentW = lines.get(0).leftWidth();
        for (Row row : rows) {
            HudCard.Line line = HudCard.Line.of(font, row.label(), SBSTheme.TEXT_MUTED, row.value(), row.colour());
            lines.add(line);
            contentW = Math.max(contentW, line.width(12));
        }
        int width = Math.max(MIN_WIDTH, contentW + PAD * 2);
        int height = PAD * 2 + lineH * lines.size() - 2;
        return new HudCard.Snapshot(List.copyOf(lines), width, height, key);
    }

    private record Row(String label, String value, int colour) {
    }

    private static String signed(long coins) {
        return coins < 0 ? "-" + NumberDisplay.format(-coins) : NumberDisplay.format(coins);
    }

    private static int colour(long coins) {
        return coins < 0 ? LOSS : GAIN;
    }

    private static String cropName(String bazaarId) {
        for (CropType crop : CropType.values()) {
            if (crop.bazaarId().equals(bazaarId)) {
                return crop.displayName();
            }
        }
        return bazaarId;
    }
}
