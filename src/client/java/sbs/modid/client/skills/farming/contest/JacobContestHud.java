/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.farming.contest;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemIcons;
import sbs.modid.client.skills.farming.contest.JacobContestParser.Standing;
import sbs.modid.client.skills.farming.model.CropType;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * The Jacob's Contest card ({@link HudElement#JACOB_CONTEST}): crop and icon, time left, collected,
 * your bracket, and - marked as an estimate - your pace and where it ends. Only while a contest
 * runs, and only where one can be farmed: the sidebar shows it, the Anita line named the crop, or
 * you are in the Garden.
 *
 * <p><b>No invented thresholds.</b> The sidebar is expected to say which bracket you are in, not
 * how far the next one is; until a probe shows a next-threshold line, the card shows the bracket and
 * nothing about the distance.
 */
public final class JacobContestHud {

    private static final int PAD = 4;
    private static final int ICON = 16;
    private static final int ROW = 10;

    private JacobContestHud() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.FarmingSettings cfg = ConfigManager.getInstance().get().farming;
        JacobContestTracker tracker = JacobContestTracker.getInstance();
        if (!cfg.contestCard || !tracker.active() || HudLayout.isHidden(HudElement.JACOB_CONTEST)
                || Minecraft.getInstance().player == null) {
            return;
        }
        Standing s = tracker.standing();
        CropType crop = tracker.crop();
        if (!s.contest() && crop == null && !SkyBlockLocation.onIsland("The Garden")) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        String title = crop == null ? "Jacob's Contest" : crop.displayName() + " Contest";
        List<String> lines = new ArrayList<>();
        int left = tracker.secondsLeft();
        lines.add("Time left: " + (left < 0 ? "?" : clock(left)));
        lines.add("Collected: " + (s.collected() < 0 ? "?" : NumberDisplay.format(s.collected())));
        if (s.bracket() != null) {
            lines.add("Bracket: " + s.bracket().label());
        }
        if (cfg.contestProjection) {
            double perMinute = tracker.perMinute();
            if (perMinute >= 0) {
                lines.add("Rate: " + NumberDisplay.format(Math.round(perMinute)) + "/min");
                long end = tracker.projection();
                if (end >= 0) {
                    lines.add("≈ " + NumberDisplay.format(end) + " at end (est.)");
                }
            }
        }
        if (s.collected() < 0) {
            lines.add("§8Waiting for the sidebar");
        }

        int w = PAD * 2 + ICON + 4 + font.width(title);
        for (String line : lines) {
            w = Math.max(w, PAD * 2 + font.width(line));
        }
        int h = PAD * 2 + ICON + 2 + ROW * lines.size();
        HudElement.Bounds b = HudElement.JACOB_CONTEST.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(HudElement.JACOB_CONTEST, x, y, w, h);
        HudLayout.begin(g, HudElement.JACOB_CONTEST);
        g.fill(x, y, x + w, y + h, SBSTheme.HUD_CARD_BG);
        g.outline(x, y, w, h, SBSTheme.HUD_CARD_BORDER);
        if (crop != null) {
            ItemStack icon = SkyBlockItemIcons.getInstance().icon(crop.bazaarId(), null, 1);
            if (!icon.isEmpty()) {
                g.item(icon, x + PAD, y + PAD);
            }
        }
        g.text(font, title, x + PAD + ICON + 4, y + PAD + (ICON - font.lineHeight) / 2, SBSTheme.ACCENT, true);
        int cy = y + PAD + ICON + 2;
        for (String line : lines) {
            g.text(font, line, x + PAD, cy, SBSTheme.TEXT, false);
            cy += ROW;
        }
        HudLayout.end(g);
    }

    static String clock(int seconds) {
        return (seconds / 60) + "m " + String.format("%02d", seconds % 60) + "s";
    }
}
