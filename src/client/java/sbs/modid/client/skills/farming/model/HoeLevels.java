/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.farming.model;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Hoe Level card: how far the tool in your hand is through its current level, with the levels
 * past the cap shown as overflow, plus the option to silence the level-up jingle.
 *
 * <p><b>Read from the tool, never remembered.</b> The level and its progress live in the tool's own
 * lore, which Hypixel keeps correct across sessions, profiles and trades. Parsing it every frame
 * costs nothing and cannot drift; caching it could. Unrecognised wording is logged once every few
 * seconds as {@code [SBS][Hoe]} with the candidate lines, so the patterns can be tuned against a
 * live tool rather than guessed at.
 *
 * <p><b>The mute.</b> Levelling a hoe mid-farm fires a celebratory sound every few minutes, which
 * is exactly the kind of thing that stops being a reward and starts being noise. The mute opens a
 * short window around the level-up chat line and drops sounds inside it (see
 * {@code FarmingSoundMuteMixin}) instead of muting a sound id outright – the same jingle is used
 * elsewhere, and only the farming one is unwanted.
 */
public final class HoeLevels {

    private static final HoeLevels INSTANCE = new HoeLevels();

    /** How long after a level-up line sounds are dropped. Covers the jingle, nothing after it. */
    private static final long MUTE_WINDOW_MS = 2_500L;

    /** "[Lvl 34]" in the name, or "Level 34" / "Level: 34/50" in the lore. */
    private static final Pattern NAME_LEVEL = Pattern.compile("\\[Lvl\\s*(\\d+)]");
    private static final Pattern LORE_LEVEL = Pattern.compile("(?i)\\blevel:?\\s*(\\d+)(?:\\s*/\\s*(\\d+))?");
    private static final Pattern PERCENT = Pattern.compile("([\\d.,]+)\\s*%");
    private static final Pattern FRACTION = Pattern.compile(
            "([\\d][\\d.,]*[kKmMbB]?)\\s*/\\s*([\\d][\\d.,]*[kKmMbB]?)");
    /** "Your Melon Dicer 3 reached level 12!" and the wordings around it. */
    private static final Pattern LEVEL_UP = Pattern.compile(
            "(?i)(hoe|dicer|chopper|cutter|knife).{0,40}?(reached|is now) level|level(ed)? up.{0,30}(hoe|dicer)");

    private volatile long muteUntil;
    private long lastLogAt;

    private HoeLevels() {
    }

    public static HoeLevels getInstance() {
        return INSTANCE;
    }

    private static sbs.modid.client.core.config.SBSConfig.FarmingSettings cfg() {
        return ConfigManager.getInstance().get().farming;
    }

    /** Whether farming sounds should be dropped right now (the level-up mute window is open). */
    public boolean muted() {
        return cfg().muteHoeSounds && System.currentTimeMillis() < muteUntil;
    }

    /** Called for every chat line: opens the mute window on a hoe level-up message. */
    public void onChat(String text) {
        if (!cfg().muteHoeSounds || text == null) {
            return;
        }
        if (LEVEL_UP.matcher(text).find()) {
            muteUntil = System.currentTimeMillis() + MUTE_WINDOW_MS;
        }
    }

    // ------------------------------------------------------------------ parse

    /** A tool's levelling state; {@code level < 0} means the lore did not say. */
    public record Level(int level, int max, double fraction, String progressText) {
    }

    /** Reads the levelling lines off a tool, or {@code null} when it carries none. */
    public Level read(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        int level = -1;
        int max = -1;
        double fraction = -1;
        String progressText = null;

        Matcher nameMatch = NAME_LEVEL.matcher(FarmingText.name(stack));
        if (nameMatch.find()) {
            level = Integer.parseInt(nameMatch.group(1));
        }
        List<String> unmatched = null;
        for (String raw : FarmingText.lore(stack)) {
            String line = raw.trim();
            String lower = line.toLowerCase(Locale.ROOT);
            if (level < 0) {
                Matcher m = LORE_LEVEL.matcher(line);
                if (m.find()) {
                    level = Integer.parseInt(m.group(1));
                    if (m.group(2) != null) {
                        max = Integer.parseInt(m.group(2));
                    }
                    continue;
                }
            }
            boolean progressLine = lower.contains("xp") || lower.contains("progress")
                    || lower.contains("exp");
            if (progressLine && progressText == null) {
                Matcher m = FRACTION.matcher(line);
                if (m.find()) {
                    double have = FarmingText.parseNumber(m.group(1));
                    double need = FarmingText.parseNumber(m.group(2));
                    if (have >= 0 && need > 0) {
                        fraction = Math.min(1.0, have / need);
                        progressText = FarmingText.shortNumber(have) + " / " + FarmingText.shortNumber(need);
                        continue;
                    }
                }
                Matcher p = PERCENT.matcher(line);
                if (p.find()) {
                    double percent = FarmingText.parseNumber(p.group(1));
                    if (percent >= 0) {
                        fraction = Math.min(1.0, percent / 100.0);
                        continue;
                    }
                }
                if (unmatched == null) {
                    unmatched = new ArrayList<>(3);
                }
                if (unmatched.size() < 3) {
                    unmatched.add(line);
                }
            }
        }
        if (unmatched != null) {
            long now = System.currentTimeMillis();
            if (now - lastLogAt > 5_000L) {
                lastLogAt = now;
                sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                        "[SBS][Hoe] unparsed progress lines: {}", unmatched);
            }
        }
        if (level < 0) {
            return null;
        }
        return new Level(level, max, fraction, progressText);
    }

    // ------------------------------------------------------------------ render

    /** Drawn from the HUD pass; only while a levelled farming tool is held. */
    public void render(GuiGraphicsExtractor g) {
        if (!cfg().hoeLevels || HudLayout.isHidden(HudElement.HOE_LEVEL)
                || !sbs.modid.client.skills.SkillIslands.farmingAllowed()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        ItemStack held = mc.player.getMainHandItem();
        if (!FarmingItems.isFarmingTool(held)) {
            return;
        }
        Level level = read(held);
        if (level == null) {
            return;
        }
        int max = level.max() > 0 ? level.max() : 50;
        boolean overflow = level.level() > max;
        if (overflow && !cfg().hoeOverflow) {
            return;   // capped tool, overflow display asked to stay quiet
        }

        Font font = Minecraft.getInstance().font;
        int lineH = font.lineHeight + 2;
        int pad = 5;
        int barH = 4;

        String header = FarmingText.name(held);
        if (header.length() > 22) {
            header = header.substring(0, 21) + "…";
        }
        String levelText = overflow
                ? "Lv " + level.level() + " (+" + (level.level() - max) + ")"
                : "Lv " + level.level() + (level.max() > 0 ? "/" + level.max() : "");

        int contentW = Math.max(font.width(header), font.width(levelText));
        if (level.progressText() != null) {
            contentW = Math.max(contentW, font.width(level.progressText()));
        }
        int width = Math.max(120, contentW + pad * 2);
        int rows = level.progressText() != null ? 1 : 0;
        int height = pad * 2 + lineH * (2 + rows) + (level.fraction() >= 0 ? barH + 3 : 0) - 2;

        HudElement.Bounds b = HudElement.HOE_LEVEL.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(HudElement.HOE_LEVEL, x, y, width, height);

        HudLayout.begin(g, HudElement.HOE_LEVEL);
        HudCard.draw(g, x, y, width, height);

        int ix = x + pad;
        int right = x + width - pad;
        int iy = y + pad;
        g.text(font, Component.literal(header), ix, iy, SBSTheme.ACCENT_BRIGHT);
        iy += lineH;
        g.text(font, Component.literal(levelText), ix, iy,
                overflow ? 0xFFE0A14D : SBSTheme.TEXT);
        if (level.fraction() >= 0) {
            String percent = String.format(Locale.ROOT, "%.1f%%", level.fraction() * 100);
            g.text(font, Component.literal(percent), right - font.width(percent), iy, SBSTheme.TEXT_MUTED);
        }
        iy += lineH;
        if (level.fraction() >= 0) {
            int barW = width - pad * 2;
            SciFiRender.roundedRect(g, ix, iy, barW, barH, 1, SBSTheme.PANEL_BORDER);
            int filled = (int) Math.round(barW * level.fraction());
            if (filled > 0) {
                SciFiRender.roundedRect(g, ix, iy, filled, barH, 1, SBSTheme.ACCENT_BRIGHT);
            }
            iy += barH + 3;
        }
        if (level.progressText() != null) {
            g.text(font, Component.literal(level.progressText()), ix, iy, SBSTheme.TEXT_MUTED);
        }
        HudLayout.end(g);
    }
}
