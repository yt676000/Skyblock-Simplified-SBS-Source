/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.damage.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.combat.damage.logic.DamageEstimator;
import sbs.modid.client.combat.damage.model.DamageFormat;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Damage Estimate card: what your next hit on the mob under the crosshair should do – normal
 * hit, crit, DPS and swings-to-kill, all live against the mob's current HP bar (Execute grows as
 * the bar drops, Prosecute shrinks). Self-hiding: it only exists while the overlay is on and a mob
 * is targeted. Movable / scalable via the GUI editor.
 */
public final class DamageEstimateHud {

    private static final int PAD = 6;
    private static final int LINE_GAP = 2;
    private static final int MIN_W = 110;
    private static final int COLUMN_GAP = 10;

    private static final int CRIT_COLOR = 0xFF57D977;
    private static final int HP_HIGH = 0xFF57D977;
    private static final int HP_MID = 0xFFE5C34A;
    private static final int HP_LOW = 0xFFE0605F;

    private DamageEstimateHud() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.DamageOverlaySettings cfg = ConfigManager.getInstance().get().damageOverlay;
        if (!cfg.enabled || Minecraft.getInstance().player == null
                || HudLayout.isHidden(HudElement.DAMAGE_ESTIMATE)) {
            return;
        }
        DamageEstimator.Estimate estimate = DamageEstimator.getInstance().current();
        if (estimate == null) {
            return; // nothing under the crosshair
        }
        HudElement.Bounds bounds = HudElement.DAMAGE_ESTIMATE.defaultBounds(g.guiWidth(), g.guiHeight());
        HudLayout.begin(g, HudElement.DAMAGE_ESTIMATE);
        draw(g, estimate, cfg, (int) bounds.x(), (int) bounds.y());
        HudLayout.end(g);
    }

    private static void draw(GuiGraphicsExtractor g, DamageEstimator.Estimate est,
                             SBSConfig.DamageOverlaySettings cfg, int x, int y) {
        Font font = Minecraft.getInstance().font;
        String title = est.level() > 0 ? est.mobName() + " Lv" + est.level() : est.mobName();

        String hpLine;
        int hpColor;
        double pct = est.maxHp() > 0 ? est.currentHp() / est.maxHp() * 100.0 : 100;
        if (est.maxHp() > 0) {
            hpLine = DamageFormat.compact(est.currentHp()) + "/" + DamageFormat.compact(est.maxHp())
                    + "❤ (" + Math.round(pct) + "%)";
            hpColor = pct > 60 ? HP_HIGH : pct > 25 ? HP_MID : HP_LOW;
        } else {
            hpLine = "HP unknown";
            hpColor = SBSTheme.TEXT_MUTED;
        }

        List<String[]> rows = new ArrayList<>(4);
        String footer = null;
        if (est.statsMissing()) {
            rows.add(new String[] {"Open the SkyBlock Menu", ""});
            rows.add(new String[] {"once to capture stats", ""});
        } else if (est.result() != null) {
            // Combat level is the single biggest additive term (+4%/level, up to +210%), so an
            // uncaptured one is called out rather than quietly costing a factor of three.
            if (cfg.combatLevel <= 0) {
                rows.add(new String[] {"⚠ Combat lvl unset", ""});
            }
            rows.add(new String[] {est.firstHit() ? "Hit (1st)" : "Hit",
                    DamageFormat.compact(est.result().normal())});
            rows.add(new String[] {"Crit", DamageFormat.compact(est.result().crit())});
            if (cfg.showDps) {
                rows.add(new String[] {"DPS", DamageFormat.compact(est.result().dps())});
            }
            if (cfg.showHitsToKill) {
                rows.add(new String[] {"Kill", est.result().hitsToKill() < 0
                        ? "500+ hits" : "~" + est.result().hitsToKill() + " hits"});
            }
            if (cfg.showDetails) {
                footer = "cmb " + cfg.combatLevel + " · def " + est.defense() + (est.calibrated()
                        ? String.format(Locale.ROOT, " · cal ×%.2f", est.calibration())
                        : " · cal —");
            }
        }

        int contentW = Math.max(font.width(title), font.width(hpLine));
        for (String[] row : rows) {
            contentW = Math.max(contentW, font.width(row[0]) + COLUMN_GAP + font.width(row[1]));
        }
        if (footer != null) {
            contentW = Math.max(contentW, font.width(footer));
        }

        int lineH = font.lineHeight + LINE_GAP;
        int lines = 2 + rows.size() + (footer != null ? 1 : 0);
        int width = Math.max(MIN_W, PAD * 2 + contentW);
        int height = PAD * 2 + lineH * lines - LINE_GAP;
        HudLayout.measure(HudElement.DAMAGE_ESTIMATE, x, y, width, height);

        HudCard.draw(g, x, y, width, height);

        int ix = x + PAD;
        int rightEdge = x + width - PAD;
        int iy = y + PAD;
        g.text(font, Component.literal(title), ix, iy, SBSTheme.ACCENT);
        iy += lineH;
        g.text(font, Component.literal(hpLine), ix, iy, hpColor);
        iy += lineH;

        for (String[] row : rows) {
            int labelColor = est.statsMissing() ? SBSTheme.TEXT_MUTED
                    : row[0].startsWith("Crit") ? CRIT_COLOR : SBSTheme.TEXT_MUTED;
            g.text(font, Component.literal(row[0]), ix, iy, labelColor);
            if (!row[1].isEmpty()) {
                g.text(font, Component.literal(row[1]), rightEdge - font.width(row[1]), iy,
                        row[0].startsWith("Crit") ? CRIT_COLOR : SBSTheme.TEXT);
            }
            iy += lineH;
        }
        if (footer != null) {
            g.text(font, Component.literal(footer), ix, iy, SBSTheme.TEXT_MUTED);
        }
    }
}
