/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.garden.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.skills.farming.model.FarmingText;
import sbs.modid.client.skills.garden.logic.PestTracker;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The rows of Hypixel's Pests widget that the pest card does not already cover - spray, repellent
 * and the drop bonus - as their own card, plus the Pesthunter Phillip buff timer, which no widget
 * carries at all.
 *
 * <p>Values are shown exactly as the widget words them ("None", "INACTIVE", "MAX PESTS"): they are
 * states, not numbers to recompute, and rewording them would only make them harder to match against
 * the tab list they came from.
 *
 * <p>Garden-only by construction rather than by an island check: Hypixel publishes the Pests widget
 * on the Garden and nowhere else, so {@link PestTracker#onGarden()} is both the data source and the
 * visibility rule.
 */
public final class PestStatusHud {

    private static final int PAD = 5;

    private PestStatusHud() {
    }

    private static SBSConfig.PestStatusSettings cfg() {
        return ConfigManager.getInstance().get().pestStatus;
    }

    public static void render(GuiGraphicsExtractor g) {
        var cfg = cfg();
        if (!cfg.enabled || Minecraft.getInstance().player == null
                || HudLayout.isHidden(HudElement.PEST_STATUS)) {
            return;
        }
        PestTracker pests = PestTracker.getInstance();
        if (!pests.onGarden()) {
            return;
        }

        List<String[]> rows = new ArrayList<>(4);
        addRow(rows, pests, cfg.showSpray, "spray", "Spray");
        addRow(rows, pests, cfg.showRepellent, "repellent", "Repellent");
        addRow(rows, pests, cfg.showBonus, "bonus", "Bonus");
        // Not the raw widget row: cooldown() is the one answer, so Custom Cooldown applies here too.
        PestTracker.Cooldown cooldown = pests.cooldown();
        if (cfg.showCooldown && cooldown.kind() != PestTracker.CooldownKind.UNKNOWN) {
            rows.add(new String[]{"Cooldown", PestTracker.cooldownText(cooldown)});
        }
        addPhillipRow(rows, pests, cfg.showPhillip);
        if (rows.isEmpty()) {
            return;
        }

        Font font = Minecraft.getInstance().font;
        int lineH = font.lineHeight + 2;
        String header = "Pest Status";
        int contentW = font.width(header);
        for (String[] row : rows) {
            contentW = Math.max(contentW, font.width(row[0]) + 12 + font.width(row[1]));
        }
        int width = Math.max(120, contentW + PAD * 2);
        int height = PAD * 2 + lineH * (1 + rows.size()) - 2;

        HudElement.Bounds b = HudElement.PEST_STATUS.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(HudElement.PEST_STATUS, x, y, width, height);

        HudLayout.begin(g, HudElement.PEST_STATUS);
        HudCard.draw(g, x, y, width, height);

        int ix = x + PAD;
        int right = x + width - PAD;
        int iy = y + PAD;
        g.text(font, Component.literal(header), ix, iy, SBSTheme.ACCENT_BRIGHT);
        iy += lineH;
        for (String[] row : rows) {
            g.text(font, Component.literal(row[0]), ix, iy, SBSTheme.TEXT_MUTED);
            g.text(font, Component.literal(row[1]), right - font.width(row[1]), iy, valueColor(row[1]));
            iy += lineH;
        }
        HudLayout.end(g);
    }

    private static void addRow(List<String[]> rows, PestTracker pests, boolean on,
                               String key, String label) {
        if (!on) {
            return;
        }
        String value = pests.statusLine(key);
        if (value != null && !value.isBlank()) {
            rows.add(new String[]{label, value});
        }
    }

    /**
     * The Farming Fortune buff from handing pests to Pesthunter Phillip, counting down: "☘200 24m 12s"
     * while it runs, then "expired" for a moment so the end is not a silent disappearance.
     *
     * <p>The odd row out: it is read from Phillip's chat line, not from the Pests widget, because the
     * widget does not carry it. Which also means it is only known from the hand-in onwards - after a
     * relog the row stays away rather than guessing at a buff it never saw start.
     */
    private static void addPhillipRow(List<String[]> rows, PestTracker pests, boolean on) {
        if (!on || !pests.phillipKnown()) {
            return;
        }
        long remaining = pests.phillipRemainingMs();
        rows.add(new String[]{"Phillip", remaining == 0
                ? "expired"
                : "☘" + pests.phillipFortune() + "  " + FarmingText.duration(remaining)});
    }

    /**
     * "None" and "INACTIVE" mean a buff you are not getting, so they read muted; anything else is an
     * active state and reads normal. "MAX PESTS" is the one warning the widget hands out, and an
     * expired Phillip buff is the other.
     */
    private static int valueColor(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("max pest") || lower.equals("expired")) {
            return 0xFFFF6060;
        }
        if (lower.equals("none") || lower.contains("inactive")) {
            return SBSTheme.TEXT_MUTED;
        }
        return 0xFF57D977;
    }
}
