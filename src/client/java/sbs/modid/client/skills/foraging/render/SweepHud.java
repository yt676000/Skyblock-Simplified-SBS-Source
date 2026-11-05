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
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.skills.SkillIslands;
import sbs.modid.client.skills.foraging.logic.SweepTracker;
import sbs.modid.client.skills.foraging.model.ForagingItems;
import sbs.modid.client.skills.foraging.model.SweepChop;
import sbs.modid.client.skills.foraging.model.SweepStaleMode;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Sweep card: the effective Sweep that applied to the chop you just made.
 *
 * <p><b>Why this is not the stat.</b> The Sweep shown in the stat menu leaves out every conditional
 * bonus - melee-only bonuses, the first hit on a tree, tree-specific bonuses, and the penalty a
 * thrown axe takes. What this card shows is the number Hypixel itself reported for the swing, which
 * is the only place the real value is published.
 *
 * <p><b>A row whose field the message did not carry is omitted, never drawn as zero.</b> A value
 * that cannot be determined is shown as unknown or not at all; a confident wrong number is the one
 * output worse than none.
 *
 * <p>The card measures itself from its rows, so {@link HudElement#FORAGING_SWEEP}'s default bounds
 * are only the anchor the editor moves and scales about.
 */
public final class SweepHud {

    /** Width steps the card snaps to, so a value going 9 -> 10 does not twitch the frame. */
    private static final int WIDTH_STEP = 8;

    /** Minimum card width, so a one-row card is still a card. */
    private static final int MIN_WIDTH = 96;

    /** The widest the card has been recently, and when that was last raised. */
    private static int highWaterWidth;
    private static long highWaterAt;

    /** How long the card holds its widest measurement before it is allowed to shrink. */
    private static final long SHRINK_HOLD_MS = 1_500L;

    private static final int PAD = 5;

    /**
     * The card as last built, {@code null} when the chop had nothing to show. Rebuilt only when its
     * key below moves: a new chop or session figure, the stale dimming, or a row setting.
     * Render thread only.
     */
    private static HudCard.Snapshot card;
    private static int builtTracker = -1;
    private static boolean builtDim;
    private static boolean builtToughness;
    private static boolean builtBlocks;
    private static boolean builtSession;

    private SweepHud() {
    }

    private static SBSConfig.SweepSettings cfg() {
        return ConfigManager.getInstance().get().sweep;
    }

    /** Drawn from the HUD pass; self-hiding when there is nothing to say. */
    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.SweepSettings cfg = cfg();
        if (!cfg.enabled || HudLayout.isHidden(HudElement.FORAGING_SWEEP)) {
            return;
        }
        if (cfg.onlyForagingIslands && !SkillIslands.foragingAllowed()) {
            return;
        }
        SweepTracker tracker = SweepTracker.getInstance();
        if (cfg.requireRecentChop && !tracker.choppedWithin(cfg.recentChopSeconds)) {
            return;
        }
        if (cfg.requireAxeInHand && !holdingAxe()) {
            return;
        }
        SweepChop chop = tracker.last();
        if (chop == null) {
            return;
        }
        boolean stale = tracker.stale();
        if (stale && cfg.staleMode == SweepStaleMode.HIDE) {
            return;
        }
        // GREY is the only mode that dims; KEEP deliberately reads as live because the player asked
        // for a stable readout while comparing gear.
        boolean dim = stale && cfg.staleMode == SweepStaleMode.GREY;

        int version = tracker.version();
        if (version != builtTracker || dim != builtDim || cfg.showToughness != builtToughness
                || cfg.showBlocks != builtBlocks || cfg.showSessionStats != builtSession) {
            builtTracker = version;
            builtDim = dim;
            builtToughness = cfg.showToughness;
            builtBlocks = cfg.showBlocks;
            builtSession = cfg.showSessionStats;
            card = build(tracker, chop, cfg, dim);
        }
        HudCard.Snapshot current = card;
        if (current == null) {
            return;
        }

        Font font = Minecraft.getInstance().font;
        int lineH = font.lineHeight + 2;
        // The snapshot's width is the raw measurement; the step and the shrink hold are applied per
        // frame because the hold is a clock of its own.
        int width = quantise(current.width());
        int height = current.height();

        HudElement.Bounds b = HudElement.FORAGING_SWEEP.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(HudElement.FORAGING_SWEEP, x, y, width, height);

        HudLayout.begin(g, HudElement.FORAGING_SWEEP);
        HudCard.draw(g, x, y, width, height);

        int ix = x + PAD;
        int right = x + width - PAD;
        int iy = y + PAD;
        for (HudCard.Line line : current.rows()) {
            HudCard.drawLine(g, font, line, ix, right, iy);
            iy += lineH;
        }
        HudLayout.end(g);
    }

    /** The card's rows and unstepped size, or {@code null} when the chop carries nothing to show. */
    private static HudCard.Snapshot build(SweepTracker tracker, SweepChop chop,
                                          SBSConfig.SweepSettings cfg, boolean dim) {
        List<String[]> rows = new ArrayList<>(5);
        if (chop.hasSweep()) {
            rows.add(new String[] {"Sweep", "∮" + number(chop.effectiveSweep())});
        }
        if (cfg.showToughness && chop.hasToughness()) {
            rows.add(new String[] {"Toughness", number(chop.toughness())});
        }
        if (cfg.showBlocks && chop.hasBlocks()) {
            rows.add(new String[] {"Blocks", String.valueOf(chop.blocksBroken())});
        }
        if (chop.delivery() != SweepChop.Delivery.UNKNOWN) {
            rows.add(new String[] {"Hit",
                    chop.delivery() == SweepChop.Delivery.THROWN ? "Thrown" : "Melee"});
        }
        if (cfg.showSessionStats && tracker.sessionChops() > 0) {
            rows.add(new String[] {"Max", "∮" + number(tracker.sessionMax())});
            rows.add(new String[] {"Avg", "∮" + number(tracker.sessionAverage())});
        }
        if (rows.isEmpty()) {
            return null;
        }

        Font font = Minecraft.getInstance().font;
        int lineH = font.lineHeight + 2;
        List<HudCard.Line> lines = new ArrayList<>(rows.size() + 1);
        lines.add(HudCard.Line.of(font, "Sweep", dim ? SBSTheme.TEXT_MUTED : SBSTheme.ACCENT_BRIGHT));
        int contentW = lines.get(0).leftWidth();
        for (String[] row : rows) {
            int colour = dim ? SBSTheme.TEXT_MUTED
                    : (row[0].equals("Sweep") ? 0xFF57D977 : SBSTheme.TEXT);
            HudCard.Line line = HudCard.Line.of(font, row[0], SBSTheme.TEXT_MUTED, row[1], colour);
            lines.add(line);
            contentW = Math.max(contentW, line.width(12));
        }
        int width = Math.max(MIN_WIDTH, contentW + PAD * 2);
        int height = PAD * 2 + lineH * lines.size() - 2;
        return new HudCard.Snapshot(List.copyOf(lines), width, height, null);
    }

    /** Whether either hand holds an axe - the offhand counts, since a thrown axe leaves the main one. */
    private static boolean holdingAxe() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return false;
        }
        return ForagingItems.isAxe(minecraft.player.getMainHandItem())
                || ForagingItems.isAxe(minecraft.player.getOffhandItem());
    }

    /**
     * Rounds the measured width up to a step and holds the widest recent value.
     *
     * <p>The number on this card changes on every chop, several times a second. Without this the
     * frame would twitch with each new digit - visible under any style, and worst under the
     * outline-only look, where the ring <i>is</i> the whole shape and has nothing to hide behind.
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

    /** Hypixel's numbers are whole far more often than not; a decimal is only shown when there is one. */
    private static String number(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
