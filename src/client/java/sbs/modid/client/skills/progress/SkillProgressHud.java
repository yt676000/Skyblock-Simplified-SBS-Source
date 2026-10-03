/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.progress;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Skill Progress overlay: what is being levelled right now, how far it is, and how long the rest
 * will take.
 *
 * <p>Deliberately Minecraft-flavoured rather than SBS sci-fi – a flat dark panel, drop-shadowed
 * text and the vanilla experience-green bar, so it reads as part of the game's own HUD.
 *
 * <p>Only drawn while a skill is actually earning XP ({@link SkillTracker#active()}); it disappears
 * on its own a few seconds after the last gain. Position and size come from the shared GUI editor
 * via {@link HudElement#SKILL_PROGRESS}.
 *
 * <p><b>Extending.</b> The body is a list of lines built in {@link #buildLines}; a new statistic is
 * one entry there (and, if it should be optional, one toggle in
 * {@link SBSConfig.SkillOverlaySettings}). The panel measures itself from the lines, so nothing else
 * needs touching. A new skill needs at most an icon in {@link #ICONS} – an unknown skill still
 * renders, just with the default icon.
 */
public final class SkillProgressHud {

    /** Panel padding and the fixed row metrics the default bounds are derived from. */
    private static final int PAD = 4;
    private static final int ICON = 16;
    private static final int BAR_HEIGHT = 5;
    private static final int LINE_GAP = 2;

    /** Vanilla-ish HUD colours. */
    private static final int PANEL_BG = 0xB0100010;
    private static final int PANEL_BORDER = 0x50FFFFFF;
    private static final int BAR_TRACK = 0xFF3E3E3E;
    private static final int BAR_FILL = 0xFF80E62E;   // vanilla experience green
    private static final int TEXT = 0xFFFFFFFF;
    private static final int TEXT_MUTED = 0xFFAAAAAA;

    /** Skill -> the item shown as its icon. Anything unlisted falls back to {@link #DEFAULT_ICON}. */
    private static final Map<String, Item> ICONS = Map.ofEntries(
            Map.entry("farming", Items.GOLDEN_HOE),
            Map.entry("mining", Items.STONE_PICKAXE),
            Map.entry("combat", Items.IRON_SWORD),
            Map.entry("foraging", Items.JUNGLE_SAPLING),
            Map.entry("fishing", Items.FISHING_ROD),
            Map.entry("enchanting", Items.ENCHANTING_TABLE),
            Map.entry("alchemy", Items.BREWING_STAND),
            Map.entry("taming", Items.LEAD),
            Map.entry("carpentry", Items.CRAFTING_TABLE),
            Map.entry("runecrafting", Items.MAGMA_CREAM),
            Map.entry("social", Items.EMERALD));
    private static final Item DEFAULT_ICON = Items.EXPERIENCE_BOTTLE;

    private SkillProgressHud() {
    }

    private static SBSConfig.SkillOverlaySettings cfg() {
        return ConfigManager.getInstance().get().skillOverlay;
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        SkillTracker tracker = SkillTracker.getInstance();
        if (!cfg().enabled || !tracker.active() || Minecraft.getInstance().player == null
                || HudLayout.isHidden(HudElement.SKILL_PROGRESS)) {
            return;
        }
        HudElement.Bounds bounds = HudElement.SKILL_PROGRESS.defaultBounds(g.guiWidth(), g.guiHeight());
        HudLayout.begin(g, HudElement.SKILL_PROGRESS);
        draw(g, tracker, (int) bounds.x(), (int) bounds.y(), (int) bounds.w());
        HudLayout.end(g);
    }

    private static void draw(GuiGraphicsExtractor g, SkillTracker tracker, int x, int y, int width) {
        Font font = Minecraft.getInstance().font;
        List<String> lines = buildLines(tracker);

        int bodyH = ICON + LINE_GAP + BAR_HEIGHT
                + lines.size() * (font.lineHeight + LINE_GAP);
        int height = bodyH + PAD * 2;
        HudLayout.measure(HudElement.SKILL_PROGRESS, x, y, width, height);

        g.fill(x, y, x + width, y + height, PANEL_BG);
        g.outline(x, y, width, height, PANEL_BORDER);

        int ix = x + PAD;
        int iy = y + PAD;

        // Header row: icon, "Mining 27" on the left, the percentage hard right.
        g.item(new ItemStack(icon(tracker.skill())), ix, iy);
        String title = tracker.skill() + levelSuffix(tracker.level());
        g.text(font, Component.literal(title), ix + ICON + 4, iy + (ICON - font.lineHeight) / 2, TEXT, true);
        String pct = String.format(Locale.ROOT, "%.1f%%", tracker.progress() * 100.0);
        g.text(font, Component.literal(pct), x + width - PAD - font.width(pct),
                iy + (ICON - font.lineHeight) / 2, TEXT, true);

        // Progress bar.
        int barY = iy + ICON + LINE_GAP;
        int barW = width - PAD * 2;
        g.fill(ix, barY, ix + barW, barY + BAR_HEIGHT, BAR_TRACK);
        int filled = (int) Math.round(barW * tracker.progress());
        if (filled > 0) {
            g.fill(ix, barY, ix + filled, barY + BAR_HEIGHT, BAR_FILL);
        }

        int ly = barY + BAR_HEIGHT + LINE_GAP;
        for (String line : lines) {
            g.text(font, Component.literal(line), ix, ly, TEXT_MUTED, true);
            ly += font.lineHeight + LINE_GAP;
        }
    }

    /**
     * The body lines, in order. Each optional statistic is skipped both when switched off and when
     * it has nothing meaningful to say yet, so the panel never shows a placeholder.
     */
    private static List<String> buildLines(SkillTracker tracker) {
        SBSConfig.SkillOverlaySettings cfg = cfg();
        List<String> lines = new ArrayList<>(3);

        lines.add(compact(tracker.currentXp()) + " / " + compact(tracker.requiredXp())
                + "  (" + compact(tracker.remainingXp()) + " to go)");

        if (cfg.showRate) {
            double perHour = tracker.xpPerHour();
            if (perHour > 0) {
                lines.add(compact(perHour) + " XP/h");
            }
        }
        if (cfg.showEta) {
            double eta = tracker.etaSeconds();
            if (eta >= 0) {
                lines.add(formatDuration(eta) + " to level "
                        + nextLevelLabel(tracker.level()));
            }
        }
        if (cfg.showActions) {
            int actions = tracker.actionsRemaining();
            if (actions > 0) {
                lines.add(compact(actions) + " actions left");
            }
        }
        return lines;
    }

    // ------------------------------------------------------------------
    // Formatting
    // ------------------------------------------------------------------

    private static Item icon(String skill) {
        if (skill == null) {
            return DEFAULT_ICON;
        }
        return ICONS.getOrDefault(skill.toLowerCase(Locale.ROOT), DEFAULT_ICON);
    }

    /** " 27" for a known level, empty when the requirement wasn't in the table. */
    private static String levelSuffix(int level) {
        return level == SkillXpTable.UNKNOWN_LEVEL ? "" : " " + level;
    }

    private static String nextLevelLabel(int level) {
        return level == SkillXpTable.UNKNOWN_LEVEL ? "up" : String.valueOf(level + 1);
    }

    /**
     * A duration in the largest unit that still reads naturally: seconds under a minute, minutes
     * (with seconds) under an hour, hours and minutes above that.
     */
    static String formatDuration(double seconds) {
        long total = (long) Math.ceil(seconds);
        if (total < 60) {
            return total + "s";
        }
        if (total < 3600) {
            return (total / 60) + "m " + (total % 60) + "s";
        }
        long hours = total / 3600;
        long minutes = (total % 3600) / 60;
        if (hours < 100) {
            return hours + "h " + minutes + "m";
        }
        return hours + "h";
    }

    /** 1,234 -> "1.2K", 1,200,000 -> "1.2M" – or the full XP figure, per "Shorten Numbers". */
    static String compact(double value) {
        return sbs.modid.client.core.util.NumberDisplay.format(value);
    }
}
