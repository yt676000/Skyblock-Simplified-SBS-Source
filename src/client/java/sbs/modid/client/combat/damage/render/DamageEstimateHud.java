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
import sbs.modid.client.combat.damage.logic.DamageEstimator;
import sbs.modid.client.combat.damage.model.DamageFormat;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.ui.hud.render.HudCard;
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
 *
 * <p>The estimator publishes a new {@link DamageEstimator.Estimate} on every scan whether or not
 * anything moved, so the card keys its {@link HudCard.Snapshot} on the estimate's value (a record)
 * plus the settings it shows - an unchanged mob rebuilds nothing, and a frame only draws.
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

    /** What a snapshot was built for: the estimate by value, and the settings that shape the rows. */
    private record Version(DamageEstimator.Estimate estimate, int combatLevel, boolean showDps,
                           boolean showHitsToKill, boolean showDetails) {
    }

    private static volatile HudCard.Snapshot snapshot;

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
        HudCard.Snapshot card = snapshot;
        // Field by field against the built version, so an unchanged frame allocates no key.
        if (card == null || !(card.version() instanceof Version built)
                || (built.estimate() != estimate && !built.estimate().equals(estimate))
                || built.combatLevel() != cfg.combatLevel || built.showDps() != cfg.showDps
                || built.showHitsToKill() != cfg.showHitsToKill || built.showDetails() != cfg.showDetails) {
            card = build(new Version(estimate, cfg.combatLevel, cfg.showDps, cfg.showHitsToKill,
                    cfg.showDetails));
            snapshot = card;
        }
        HudElement.Bounds bounds = HudElement.DAMAGE_ESTIMATE.defaultBounds(g.guiWidth(), g.guiHeight());
        HudLayout.begin(g, HudElement.DAMAGE_ESTIMATE);
        draw(g, card, (int) bounds.x(), (int) bounds.y());
        HudLayout.end(g);
    }

    private static HudCard.Snapshot build(Version version) {
        DamageEstimator.Estimate est = version.estimate();
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
            if (version.combatLevel() <= 0) {
                rows.add(new String[] {"⚠ Combat lvl unset", ""});
            }
            rows.add(new String[] {est.firstHit() ? "Hit (1st)" : "Hit",
                    DamageFormat.compact(est.result().normal())});
            rows.add(new String[] {"Crit", DamageFormat.compact(est.result().crit())});
            if (version.showDps()) {
                rows.add(new String[] {"DPS", DamageFormat.compact(est.result().dps())});
            }
            if (version.showHitsToKill()) {
                rows.add(new String[] {"Kill", est.result().hitsToKill() < 0
                        ? "500+ hits" : "~" + est.result().hitsToKill() + " hits"});
            }
            if (version.showDetails()) {
                footer = "cmb " + version.combatLevel() + " · def " + est.defense() + (est.calibrated()
                        ? String.format(Locale.ROOT, " · cal ×%.2f", est.calibration())
                        : " · cal —");
            }
        }

        List<HudCard.Line> lines = new ArrayList<>(rows.size() + 3);
        lines.add(HudCard.Line.of(font, title, SBSTheme.ACCENT));
        lines.add(HudCard.Line.of(font, hpLine, hpColor));
        int contentW = Math.max(lines.get(0).leftWidth(), lines.get(1).leftWidth());
        for (String[] row : rows) {
            boolean crit = row[0].startsWith("Crit");
            int labelColor = est.statsMissing() ? SBSTheme.TEXT_MUTED
                    : crit ? CRIT_COLOR : SBSTheme.TEXT_MUTED;
            HudCard.Line line = HudCard.Line.of(font, row[0], labelColor, row[1],
                    crit ? CRIT_COLOR : SBSTheme.TEXT);
            contentW = Math.max(contentW, line.width(COLUMN_GAP));
            lines.add(line);
        }
        if (footer != null) {
            HudCard.Line line = HudCard.Line.of(font, footer, SBSTheme.TEXT_MUTED);
            contentW = Math.max(contentW, line.leftWidth());
            lines.add(line);
        }

        int lineH = font.lineHeight + LINE_GAP;
        int width = Math.max(MIN_W, PAD * 2 + contentW);
        int height = PAD * 2 + lineH * lines.size() - LINE_GAP;
        return new HudCard.Snapshot(List.copyOf(lines), width, height, version);
    }

    private static void draw(GuiGraphicsExtractor g, HudCard.Snapshot card, int x, int y) {
        Font font = Minecraft.getInstance().font;
        HudLayout.measure(HudElement.DAMAGE_ESTIMATE, x, y, card.width(), card.height());

        HudCard.draw(g, x, y, card.width(), card.height());

        int lineH = font.lineHeight + LINE_GAP;
        int ix = x + PAD;
        int rightEdge = x + card.width() - PAD;
        int iy = y + PAD;
        for (HudCard.Line line : card.rows()) {
            HudCard.drawLine(g, font, line, ix, rightEdge, iy);
            iy += lineH;
        }
    }
}
