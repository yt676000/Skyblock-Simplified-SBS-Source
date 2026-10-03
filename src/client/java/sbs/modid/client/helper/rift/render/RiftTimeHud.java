/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.rift.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.helper.rift.logic.RiftAreas;
import sbs.modid.client.helper.rift.logic.RiftMotes;
import sbs.modid.client.helper.rift.logic.RiftState;
import sbs.modid.client.helper.rift.logic.RiftTime;
import sbs.modid.client.helper.rift.model.RiftData;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

/**
 * The Rift clock card: how long you have left, out of how long you came in with.
 *
 * <p><b>Rift-bound by construction.</b> The first thing it does is ask {@link RiftState} where it is,
 * so there is no configuration under which this draws in the Hub - the card cannot be "left on"
 * anywhere it does not belong, because being in the Rift is not a setting.
 *
 * <p>The countdown is the biggest thing on the card and it is what the colour lives on, because the
 * whole point of the feature is that the number is read from the corner of the eye while doing
 * something else. Everything under it - the maximum, the drain rate, the purse - is context that is
 * only looked at deliberately, and is drawn at normal size and muted.
 */
public final class RiftTimeHud {

    private static final int PAD = 5;

    /** The countdown is drawn at this multiple of the font size. */
    private static final float BIG_SCALE = 1.6f;

    /** Below this many seconds the countdown blinks, whatever the colour thresholds say. */
    private static final int BLINK_SECONDS = 10;

    private static final long BLINK_PERIOD_MS = 500L;

    private RiftTimeHud() {
    }

    private static SBSConfig.RiftTimeSettings cfg() {
        return ConfigManager.getInstance().get().riftTime;
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.RiftTimeSettings cfg = cfg();
        if (!cfg.enabled || !RiftState.getInstance().inRift()
                || Minecraft.getInstance().player == null
                || HudLayout.isHidden(HudElement.RIFT_TIME)) {
            return;
        }
        RiftTime time = RiftTime.getInstance();
        if (!time.available()) {
            return;
        }

        Font font = Minecraft.getInstance().font;
        int remaining = time.remaining();
        String big = RiftTime.format(remaining);
        int bigW = Math.round(font.width(big) * BIG_SCALE);
        int bigH = Math.round(font.lineHeight * BIG_SCALE);
        int lineH = font.lineHeight + 2;

        // The rows under the countdown, each optional. Built first so the card can size to them.
        String maxRow = maxRow(cfg, time);
        String drainRow = cfg.showDrainRate ? drainRow() : "";
        String motesRow = cfg.showMotes ? motesRow() : "";

        int contentW = bigW;
        for (String row : new String[] {maxRow, drainRow, motesRow}) {
            if (!row.isEmpty()) {
                contentW = Math.max(contentW, font.width(strip(row)));
            }
        }
        int rows = (maxRow.isEmpty() ? 0 : 1) + (drainRow.isEmpty() ? 0 : 1)
                + (motesRow.isEmpty() ? 0 : 1);
        int width = Math.max(90, contentW + PAD * 2);
        int barH = cfg.showBar && time.fraction() >= 0 ? 5 : 0;
        int height = PAD * 2 + bigH + (barH > 0 ? barH + 3 : 0) + rows * lineH;

        HudElement.Bounds b = HudElement.RIFT_TIME.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(HudElement.RIFT_TIME, x, y, width, height);

        HudLayout.begin(g, HudElement.RIFT_TIME);
        HudCard.draw(g, x, y, width, height);

        int iy = y + PAD;
        int color = colorFor(cfg, time);
        if (remaining <= BLINK_SECONDS && blinkOff()) {
            color = SBSTheme.TEXT_MUTED;
        }
        drawScaled(g, font, big, x + width / 2f - bigW / 2f, iy, color);
        iy += bigH;

        if (barH > 0) {
            iy += 3;
            drawBar(g, x + PAD, iy, width - PAD * 2, barH, time.fraction(), color);
            iy += barH;
        }
        for (String row : new String[] {maxRow, drainRow, motesRow}) {
            if (row.isEmpty()) {
                continue;
            }
            g.centeredText(font, Component.literal(row), x + width / 2, iy, SBSTheme.TEXT_MUTED);
            iy += lineH;
        }
        HudLayout.end(g);
    }

    /**
     * "of 12:30" - the visit's starting time, or the same with a marker when it is only a lower bound.
     *
     * <p>The marker matters: the maximum is the highest value seen this visit, which is exact when
     * the client was there at the start and an underestimate when it was not. Showing an
     * underestimate as if it were the real maximum would make the bar read fuller than it is, which
     * is the direction that gets somebody stranded.
     */
    private static String maxRow(SBSConfig.RiftTimeSettings cfg, RiftTime time) {
        if (!cfg.showMax || time.max() < 0) {
            return "";
        }
        return "of " + RiftTime.format(time.max()) + (time.maxCertain() ? "" : " §8(seen)");
    }

    /** "half speed" / "frozen" / "2x drain" for an area with a rule; nothing for ordinary ground. */
    private static String drainRow() {
        RiftData.Area area = RiftAreas.current();
        if (area == null || area.drainMultiplier == 1.0) {
            return "";
        }
        String rate = area.frozen() ? "frozen"
                : area.drainMultiplier < 1.0 ? "half speed"
                : trimZero(area.drainMultiplier) + "x drain";
        // The certainty rides on the row because this is the number most likely to be wrong: it is
        // wiki-derived until somebody has watched the clock actually do it.
        return area.certainty.trusted() ? rate : rate + " §8?";
    }

    private static String motesRow() {
        long purse = RiftMotes.getInstance().purse();
        return purse < 0 ? "" : NumberDisplay.format(purse) + " motes";
    }

    /**
     * The countdown's colour, by how much of the visit is left.
     *
     * <p>By <b>fraction</b> rather than by seconds on purpose: a player with eighty minutes of Rift
     * Time and one with eight are in completely different situations at "two minutes left", and the
     * bar next to it is a fraction too, so a colour keyed to anything else would disagree with it.
     * When the maximum is not known the fraction cannot be trusted, so it falls back to the alert
     * thresholds, which are in seconds and always meaningful.
     */
    private static int colorFor(SBSConfig.RiftTimeSettings cfg, RiftTime time) {
        double fraction = time.fraction();
        if (fraction < 0) {
            int remaining = time.remaining();
            if (cfg.criticalSeconds > 0 && remaining <= cfg.criticalSeconds) {
                return SBSTheme.WARN;
            }
            return cfg.warnSeconds > 0 && remaining <= cfg.warnSeconds
                    ? SBSTheme.HUD_OVERHEAL : SBSTheme.TEXT;
        }
        int percent = (int) Math.round(fraction * 100);
        if (percent <= cfg.criticalPercent) {
            return SBSTheme.WARN;
        }
        return percent <= cfg.warnPercent ? SBSTheme.HUD_OVERHEAL : SBSTheme.TOGGLE_ON;
    }

    private static void drawBar(GuiGraphicsExtractor g, int x, int y, int width, int height,
                                double fraction, int color) {
        g.fill(x, y, x + width, y + height, SBSTheme.PANEL_BORDER);
        int filled = (int) Math.round(Math.max(0, Math.min(1, fraction)) * (width - 2));
        if (filled > 0) {
            g.fill(x + 1, y + 1, x + 1 + filled, y + height - 1, color);
        }
    }

    /** Draws {@code text} at {@link #BIG_SCALE}, since the HUD font has one size. */
    private static void drawScaled(GuiGraphicsExtractor g, Font font, String text,
                                   float x, float y, int color) {
        org.joml.Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.translate(x, y);
        pose.scale(BIG_SCALE, BIG_SCALE);
        g.text(font, Component.literal(text), 0, 0, color);
        pose.popMatrix();
    }

    /** Half of each blink period, so the last ten seconds pulse rather than sit there. */
    private static boolean blinkOff() {
        return (System.currentTimeMillis() / BLINK_PERIOD_MS) % 2 == 1;
    }

    /** {@code 2.0 → "2"}, {@code 1.5 → "1.5"} - a drain rate should not read as "2.0x". */
    private static String trimZero(double value) {
        return value == Math.rint(value)
                ? String.valueOf((long) value)
                : String.valueOf(value);
    }

    /** Colour codes removed, for measuring a row's real width. */
    private static String strip(String text) {
        return text.replaceAll("§.", "");
    }
}
