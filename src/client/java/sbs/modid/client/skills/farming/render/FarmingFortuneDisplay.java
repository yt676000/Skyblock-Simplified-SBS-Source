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
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.skills.farming.model.CropType;
import sbs.modid.client.skills.farming.model.FarmingItems;
import sbs.modid.client.skills.farming.model.FarmingText;
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
 * The Farming Fortune card: the fortune that actually applies to the crop in your hands, not just
 * the number in the tab list.
 *
 * <p><b>Why the tab number alone is misleading.</b> Hypixel's "Farming Fortune" stat is the
 * <i>general</i> one. A Melon Dicer's "Melon Fortune", the crop-specific fortune a Theoretical Hoe
 * carries and the tool's own farming fortune only count while that crop is being farmed, so the
 * effective fortune on a melon farm is meaningfully higher than the stat you can read anywhere in
 * game. This card adds the held tool's crop-specific lines on top of the tab stat and shows the
 * resulting drop multiplier – the true fortune for what you are farming right now.
 *
 * <p>Everything is read from the held tool's lore, so a tool the mod has never heard of still
 * contributes correctly as long as Hypixel writes its fortune in the tooltip.
 */
public final class FarmingFortuneDisplay {

    private static final FarmingFortuneDisplay INSTANCE = new FarmingFortuneDisplay();

    /** "Farming Fortune: +192" – the tool's general contribution. */
    private static final Pattern TOOL_FORTUNE = Pattern.compile(
            "(?i)farming fortune:?\\s*\\+?\\s*([\\d,.]+)");
    /** "Melon Fortune: +80", "☘ Wheat Fortune: +30" – the crop-specific contribution. */
    private static final Pattern CROP_FORTUNE = Pattern.compile(
            "(?i)([A-Za-z ]+?)\\s+fortune:?\\s*\\+?\\s*([\\d,.]+)");

    private FarmingFortuneDisplay() {
    }

    public static FarmingFortuneDisplay getInstance() {
        return INSTANCE;
    }

    private static sbs.modid.client.core.config.SBSConfig.FarmingSettings cfg() {
        return ConfigManager.getInstance().get().farming;
    }

    /** A tool's fortune split, all values {@code -1} when its lore says nothing. */
    public record ToolFortune(double general, double cropSpecific, CropType crop) {
    }

    /**
     * Reads the fortune lines off a tool. The general line is skipped when matching the crop line,
     * so "Farming Fortune" can never be mistaken for a crop called "Farming".
     */
    public static ToolFortune read(ItemStack stack) {
        double general = -1;
        double cropSpecific = -1;
        CropType crop = CropType.forTool(stack);
        for (String line : FarmingText.lore(stack)) {
            String trimmed = line.trim();
            Matcher tool = TOOL_FORTUNE.matcher(trimmed);
            if (tool.find()) {
                double value = FarmingText.parseNumber(tool.group(1));
                if (value >= 0) {
                    general = Math.max(general, 0) + value;
                }
                continue;
            }
            Matcher cropMatch = CROP_FORTUNE.matcher(trimmed);
            if (cropMatch.find()) {
                CropType named = CropType.forText(cropMatch.group(1));
                if (named != null) {
                    double value = FarmingText.parseNumber(cropMatch.group(2));
                    if (value >= 0) {
                        cropSpecific = Math.max(cropSpecific, 0) + value;
                        crop = named;
                    }
                }
            }
        }
        return new ToolFortune(general, cropSpecific, crop);
    }

    // ------------------------------------------------------------------ render

    /** Drawn from the HUD pass; only while a farming tool is held. */
    public void render(GuiGraphicsExtractor g) {
        if (!cfg().farmingFortune || HudLayout.isHidden(HudElement.FARMING_FORTUNE)
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
        ToolFortune tool = read(held);
        double tabFortune = sbs.modid.client.skills.garden.logic.FarmingTracker.getInstance().fortuneValue();

        List<String[]> rows = new ArrayList<>(4);
        double effective = -1;
        if (tabFortune >= 0) {
            effective = tabFortune + Math.max(0, tool.cropSpecific());
            rows.add(new String[]{"Effective", "☘" + FarmingText.shortNumber(effective)});
            rows.add(new String[]{"General", "☘" + FarmingText.shortNumber(tabFortune)});
        }
        if (tool.cropSpecific() >= 0) {
            String label = tool.crop() != null ? tool.crop().displayName() : "Crop";
            rows.add(new String[]{label, "+" + FarmingText.shortNumber(tool.cropSpecific())});
        }
        if (tool.general() >= 0) {
            rows.add(new String[]{"Tool", "+" + FarmingText.shortNumber(tool.general())});
        }
        if (effective >= 0) {
            // Fortune is extra drops per 100: 300 fortune = 4x the base drops.
            rows.add(new String[]{"Drops", String.format(Locale.ROOT, "x%.2f", 1 + effective / 100.0)});
        }
        if (rows.isEmpty()) {
            return;
        }

        Font font = Minecraft.getInstance().font;
        int lineH = font.lineHeight + 2;
        int pad = 5;
        String header = "Farming Fortune";
        int contentW = font.width(header);
        for (String[] row : rows) {
            contentW = Math.max(contentW, font.width(row[0]) + 12 + font.width(row[1]));
        }
        int width = Math.max(124, contentW + pad * 2);
        int height = pad * 2 + lineH * (1 + rows.size()) - 2;

        HudElement.Bounds b = HudElement.FARMING_FORTUNE.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(HudElement.FARMING_FORTUNE, x, y, width, height);

        HudLayout.begin(g, HudElement.FARMING_FORTUNE);
        HudCard.draw(g, x, y, width, height);

        int ix = x + pad;
        int right = x + width - pad;
        int iy = y + pad;
        g.text(font, Component.literal(header), ix, iy, SBSTheme.ACCENT_BRIGHT);
        iy += lineH;
        for (String[] row : rows) {
            g.text(font, Component.literal(row[0]), ix, iy, SBSTheme.TEXT_MUTED);
            g.text(font, Component.literal(row[1]), right - font.width(row[1]), iy,
                    row[0].equals("Effective") ? 0xFF57D977 : SBSTheme.TEXT);
            iy += lineH;
        }
        HudLayout.end(g);
    }
}
