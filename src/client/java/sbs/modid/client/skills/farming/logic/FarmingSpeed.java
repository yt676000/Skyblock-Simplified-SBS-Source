/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.farming.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.alert.Alerts;
import sbs.modid.client.core.audio.SbsAudio;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.tracker.BreakRateTracker;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemIcons;
import sbs.modid.client.skills.farming.model.CropBlocks;
import sbs.modid.client.skills.farming.model.CropType;
import sbs.modid.client.skills.garden.logic.GardenBlueprintManager;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.Locale;

/**
 * The Farming Speed card ({@link HudElement#FARMING_SPEED}): crop blocks broken per second, the 5 s
 * average, the session peak and your usual speed, an optional 60 s graph, and a warning when you
 * fall well below your usual speed. The counting lives in {@link BreakRateTracker}; this class only
 * decides what counts (crop blocks, on the Garden) and draws it.
 *
 * <p><b>Garden only, and always there.</b> The card is drawn on the Garden whenever it is switched on,
 * whatever is in hand: idle it reads "BPS 0.0" in the paused style, and before the first crop of the
 * session "Not farming". It never fades out. Off the Garden nothing is drawn, counted or warned
 * about. The drop warning still needs active farming - {@link BreakRateTracker#checkDrop} returns
 * false while paused - so an idle card can never trip it.
 */
public final class FarmingSpeed {

    private static final FarmingSpeed INSTANCE = new FarmingSpeed();

    private static final int PAD = 4;
    private static final int ICON = 16;
    private static final int ROW = 10;
    private static final int GRAPH_H = 20;
    /** What the card shows; {@link #cardState} is the whole visibility rule. */
    enum Card { HIDDEN, NOT_FARMING, PAUSED, ACTIVE }

    private final BreakRateTracker tracker = new BreakRateTracker();
    private CropType crop;

    private FarmingSpeed() {
    }

    public static FarmingSpeed getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.FarmingSettings cfg() {
        return ConfigManager.getInstance().get().farming;
    }

    private static boolean wanted(SBSConfig.FarmingSettings cfg) {
        return cfg.speedCard || cfg.speedWarning;
    }

    /** The one place that decides whether and how the card is drawn. Pure, so it is unit-tested. */
    static Card cardState(boolean cardOn, boolean onGarden, BreakRateTracker tracker, long now) {
        if (!cardOn || !onGarden) {
            return Card.HIDDEN;
        }
        if (!tracker.everRecorded()) {
            return Card.NOT_FARMING;
        }
        return tracker.paused(now) ? Card.PAUSED : Card.ACTIVE;
    }

    private static boolean onGarden() {
        return GardenBlueprintManager.inGarden();
    }

    /** From {@code BlockBreakEvents}: one block the local player broke. */
    public void onBlockBroken(BlockPos pos, BlockState state) {
        SBSConfig.FarmingSettings cfg = cfg();
        if (!wanted(cfg) && !cfg.speedLogBlocks) {
            return;
        }
        CropType held = CropType.forHeldTool();
        CropType broken = CropBlocks.of(state, held);
        if (cfg.speedLogBlocks) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][FarmSpeed] broke {} (tool crop {}, counted as {})",
                    BuiltInRegistries.BLOCK.getKey(state.getBlock()), held, broken);
        }
        if (broken == null || !wanted(cfg) || !onGarden()) {
            return;
        }
        crop = broken;
        tracker.record(System.currentTimeMillis());
    }

    /** Once per client tick: per-second bookkeeping and the drop warning. */
    public void tick() {
        SBSConfig.FarmingSettings cfg = cfg();
        if (!wanted(cfg) || !onGarden()) {
            return;
        }
        long now = System.currentTimeMillis();
        tracker.tick(now);
        if (cfg.speedWarning && tracker.checkDrop(now, cfg.speedWarnPercent / 100.0)) {
            double usual = tracker.usual();
            Alerts.send(new Alerts.Alert("Farming slower than usual",
                    "BPS " + one(tracker.longRate(now)) + ", usually " + one(usual)
                            + " - lag, a wrong key, or stuck?",
                    SbsAudio.Tone.BLIP, null), cfg.speedAlertChannels);
        }
    }

    /** Called from the HUD render hook once per frame. */
    public void render(GuiGraphicsExtractor g) {
        SBSConfig.FarmingSettings cfg = cfg();
        long now = System.currentTimeMillis();
        if (HudLayout.isHidden(HudElement.FARMING_SPEED) || Minecraft.getInstance().player == null) {
            return;
        }
        Card state = cardState(cfg.speedCard, onGarden(), tracker, now);
        if (state == Card.HIDDEN) {
            return;
        }
        boolean paused = state != Card.ACTIVE;
        Font font = Minecraft.getInstance().font;
        String title = state == Card.NOT_FARMING ? "Not farming"
                : "BPS " + one(tracker.shortRate(now)) + " (avg " + one(tracker.longRate(now)) + ")";
        double usual = tracker.usual();
        String detail = "Peak " + one(tracker.peak()) + "  Usual " + (usual < 0 ? "..." : one(usual));
        if (paused) {
            detail = "Paused - " + detail;
        }
        int w = Math.max(PAD * 2 + ICON + 4 + font.width(title), PAD * 2 + font.width(detail));
        boolean graph = cfg.speedGraph;
        if (graph) {
            w = Math.max(w, PAD * 2 + BreakRateTracker.GRAPH_SECONDS * 2);
        }
        int h = PAD * 2 + ICON + 2 + ROW + (graph ? GRAPH_H + 2 : 0);

        HudElement.Bounds b = HudElement.FARMING_SPEED.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(HudElement.FARMING_SPEED, x, y, w, h);
        HudLayout.begin(g, HudElement.FARMING_SPEED);
        g.fill(x, y, x + w, y + h, SBSTheme.HUD_CARD_BG);
        g.outline(x, y, w, h, SBSTheme.HUD_CARD_BORDER);
        if (crop != null) {
            ItemStack icon = SkyBlockItemIcons.getInstance().icon(crop.bazaarId(), null, 1);
            if (!icon.isEmpty()) {
                g.item(icon, x + PAD, y + PAD);
            }
        }
        // Paused dims the whole card's text to the muted colour.
        int titleColour = paused ? SBSTheme.TEXT_MUTED : SBSTheme.ACCENT;
        int textColour = paused ? SBSTheme.TEXT_MUTED : SBSTheme.TEXT;
        g.text(font, title, x + PAD + ICON + 4, y + PAD + (ICON - font.lineHeight) / 2, titleColour, true);
        int cy = y + PAD + ICON + 2;
        g.text(font, detail, x + PAD, cy, textColour, false);
        cy += ROW;
        if (graph) {
            drawGraph(g, x + PAD, cy + 1, usual, paused);
        }
        HudLayout.end(g);
    }

    /** Sixty 2-px bars, scaled to the higher of the peak and the graph's own maximum. */
    private void drawGraph(GuiGraphicsExtractor g, int x, int y, double usual, boolean paused) {
        double[] values = tracker.graph();
        double max = Math.max(1, tracker.peak());
        for (double v : values) {
            max = Math.max(max, v);
        }
        int colour = paused ? SBSTheme.TEXT_MUTED : SBSTheme.ACCENT;
        for (int i = 0; i < values.length; i++) {
            int bar = (int) Math.round(values[i] / max * GRAPH_H);
            if (bar > 0) {
                g.fill(x + i * 2, y + GRAPH_H - bar, x + i * 2 + 1, y + GRAPH_H, colour);
            }
        }
        if (usual > 0) {
            int line = y + GRAPH_H - (int) Math.round(usual / max * GRAPH_H);
            g.fill(x, line, x + values.length * 2, line + 1, SBSTheme.TEXT_MUTED);
        }
    }

    private static String one(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
