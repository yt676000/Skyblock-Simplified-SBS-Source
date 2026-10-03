/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.garden.model;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.tab.TabWidgets;
import sbs.modid.client.skills.farming.model.FarmingText;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Garden Level card: the current level and how far it is to the next one, taken from the Garden
 * tab widget.
 *
 * <p>Garden levels are slow and unlocks hang off them (plots, visitors, larger composter), so
 * "where am I" is a question you ask far more often than the tab list is convenient to open. The
 * card answers it at a glance and disappears off the Garden by itself, since the widget stops being
 * served there.
 *
 * <p><b>Overflow.</b> Past the last real level Hypixel keeps counting XP without granting levels.
 * Showing those overflow levels is a choice, not a fact – it is a community convention rather than
 * something the game displays – so it sits behind its own toggle. It defaults to on, because that is
 * what people who care about the number expect to see.
 */
public final class GardenLevel {

    private static final GardenLevel INSTANCE = new GardenLevel();

    private static final long SCAN_INTERVAL_MS = 1_000L;
    /** Hide the card once the widget has been gone this long (you left the Garden). */
    private static final long HIDE_AFTER_MS = 20_000L;

    /** The level Hypixel serves; capped levels stop at the max, overflow keeps climbing. */
    private static final int MAX_LEVEL = 15;

    /** "Garden Level 12" / "Garden Level: 12" / "Garden Lvl 12". */
    private static final Pattern LEVEL = Pattern.compile(
            "(?i)garden\\s+(?:level|lvl):?\\s*(\\d+)");
    /** "Progress to Level 13: 45.7%" or a bare percentage on the widget's progress line. */
    private static final Pattern PERCENT = Pattern.compile("([\\d.,]+)\\s*%");
    /** "1,234/5,000" on the XP line. */
    private static final Pattern FRACTION = Pattern.compile(
            "([\\d][\\d.,]*[kKmMbB]?)\\s*/\\s*([\\d][\\d.,]*[kKmMbB]?)");

    private volatile int level = -1;
    private volatile double fraction = -1;
    private volatile String xpText;
    private volatile long seenAt;
    private long lastScanAt;

    private GardenLevel() {
    }

    public static GardenLevel getInstance() {
        return INSTANCE;
    }

    private static sbs.modid.client.core.config.SBSConfig.GardenSettings cfg() {
        return ConfigManager.getInstance().get().garden;
    }

    /** The current Garden level, or {@code -1} when the widget has not been seen. */
    public int level() {
        return level;
    }

    // ------------------------------------------------------------------ capture

    /** Called every client tick; reads the Garden widget out of the tab list (throttled). */
    public void onClientTick() {
        if (!cfg().gardenLevel) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastScanAt < SCAN_INTERVAL_MS) {
            return;
        }
        lastScanAt = now;

        List<String> lines = TabWidgets.lines();
        int foundLevel = -1;
        double foundFraction = -1;
        String foundXp = null;
        boolean nextIsProgress = false;
        for (String line : lines) {
            Matcher levelMatch = LEVEL.matcher(line);
            if (levelMatch.find()) {
                foundLevel = Integer.parseInt(levelMatch.group(1));
                nextIsProgress = true;
                continue;
            }
            if (!nextIsProgress) {
                continue;
            }
            // The two lines under the level line carry the progress; anything else ends the widget.
            String lower = line.toLowerCase(Locale.ROOT);
            boolean progressish = lower.contains("progress") || lower.contains("xp")
                    || lower.contains("%") || FRACTION.matcher(line).find();
            if (!progressish) {
                nextIsProgress = false;
                continue;
            }
            Matcher fraction = FRACTION.matcher(line);
            if (foundXp == null && fraction.find()) {
                double have = FarmingText.parseNumber(fraction.group(1));
                double need = FarmingText.parseNumber(fraction.group(2));
                if (have >= 0 && need > 0) {
                    foundFraction = Math.min(1.0, have / need);
                    foundXp = FarmingText.shortNumber(have) + " / " + FarmingText.shortNumber(need);
                }
            }
            if (foundFraction < 0) {
                Matcher percent = PERCENT.matcher(line);
                if (percent.find()) {
                    double value = FarmingText.parseNumber(percent.group(1));
                    if (value >= 0) {
                        foundFraction = Math.min(1.0, value / 100.0);
                    }
                }
            }
        }
        if (foundLevel >= 0) {
            level = foundLevel;
            fraction = foundFraction;
            xpText = foundXp;
            seenAt = now;
        }
    }

    // ------------------------------------------------------------------ render

    /** Drawn from the HUD pass; self-hiding away from the Garden. */
    public void render(GuiGraphicsExtractor g) {
        if (!cfg().gardenLevel || Minecraft.getInstance().player == null
                || HudLayout.isHidden(HudElement.GARDEN_LEVEL)) {
            return;
        }
        int shown = level;
        if (shown < 0 || System.currentTimeMillis() - seenAt > HIDE_AFTER_MS) {
            return;
        }
        boolean overflow = shown > MAX_LEVEL;
        if (overflow && !cfg().gardenLevelOverflow) {
            shown = MAX_LEVEL;
        }

        Font font = Minecraft.getInstance().font;
        int lineH = font.lineHeight + 2;
        int pad = 5;
        int barH = 4;
        String header = "Garden Level";
        String levelText = overflow && cfg().gardenLevelOverflow
                ? shown + " (+" + (shown - MAX_LEVEL) + ")"
                : String.valueOf(shown);
        double bar = fraction;
        String xp = xpText;

        int contentW = Math.max(font.width(header) + 10 + font.width(levelText),
                xp == null ? 0 : font.width(xp));
        int width = Math.max(118, contentW + pad * 2);
        int height = pad * 2 + lineH + (bar >= 0 ? barH + 3 : 0) + (xp != null ? lineH : 0) - 2;

        HudElement.Bounds b = HudElement.GARDEN_LEVEL.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(HudElement.GARDEN_LEVEL, x, y, width, height);

        HudLayout.begin(g, HudElement.GARDEN_LEVEL);
        HudCard.draw(g, x, y, width, height);

        int ix = x + pad;
        int right = x + width - pad;
        int iy = y + pad;
        g.text(font, Component.literal(header), ix, iy, SBSTheme.ACCENT_BRIGHT);
        g.text(font, Component.literal(levelText), right - font.width(levelText), iy,
                overflow ? 0xFFE0A14D : SBSTheme.TEXT);
        iy += lineH;
        if (bar >= 0) {
            int barW = width - pad * 2;
            SciFiRender.roundedRect(g, ix, iy, barW, barH, 1, SBSTheme.PANEL_BORDER);
            int filled = (int) Math.round(barW * bar);
            if (filled > 0) {
                SciFiRender.roundedRect(g, ix, iy, filled, barH, 1, SBSTheme.ACCENT_BRIGHT);
            }
            iy += barH + 3;
        }
        if (xp != null) {
            g.text(font, Component.literal(xp), ix, iy, SBSTheme.TEXT_MUTED);
        }
        HudLayout.end(g);
    }
}
