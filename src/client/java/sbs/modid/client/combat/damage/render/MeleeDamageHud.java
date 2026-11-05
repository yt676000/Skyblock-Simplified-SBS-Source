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
import sbs.modid.client.combat.damage.logic.MeleeDamageTracker;
import sbs.modid.client.combat.damage.model.DamageFormat;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * The Melee Damage card: your last melee hit as the headline (starred when it crit), plus the best
 * hit and the DPS over the same rolling window. Self-hiding – it only exists while the card is
 * switched on and a hit is still inside the hold time. Movable / scalable via the GUI editor.
 *
 * <p>The numbers come from {@link MeleeDamageTracker}, i.e. from Damage Attribution's own-splash
 * matching. With Damage Attribution off nothing is attributed and the card simply never appears.
 */
public final class MeleeDamageHud {

    private static final int PAD = 6;
    private static final int LINE_GAP = 2;
    private static final int MIN_W = 96;
    private static final int COLUMN_GAP = 10;

    /** Crit colour of the headline (the same green the rest of SBS uses for "good"). */
    private static final int CRIT_COLOR = 0xFF57D977;

    /**
     * The card as last built, or {@code null} while nothing was hit recently. Rebuilt only when its
     * key below moves: a new hit, a setting, or the one-second clock the decaying DPS reads.
     * Render thread only.
     */
    private static HudCard.Snapshot card;
    private static int builtTracker = -1;
    private static long builtSecond = -1;
    private static boolean builtShowMax;
    private static boolean builtShowDps;
    private static int builtHold = -1;

    private MeleeDamageHud() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.DamageAttributionSettings cfg = ConfigManager.getInstance().get().damageAttribution;
        if (!cfg.meleeHud || Minecraft.getInstance().player == null
                || HudLayout.isHidden(HudElement.MELEE_DAMAGE)) {
            return;
        }
        int tracker = MeleeDamageTracker.getInstance().version();
        long second = System.currentTimeMillis() / 1000L;
        if (tracker != builtTracker || second != builtSecond || cfg.meleeShowMax != builtShowMax
                || cfg.meleeShowDps != builtShowDps || cfg.meleeHoldSeconds != builtHold) {
            builtTracker = tracker;
            builtSecond = second;
            builtShowMax = cfg.meleeShowMax;
            builtShowDps = cfg.meleeShowDps;
            builtHold = cfg.meleeHoldSeconds;
            MeleeDamageTracker.Snapshot snapshot = MeleeDamageTracker.getInstance().snapshot();
            card = snapshot == null ? null : build(snapshot, cfg);
        }
        HudCard.Snapshot current = card;
        if (current == null) {
            return; // nothing hit recently
        }
        HudElement.Bounds bounds = HudElement.MELEE_DAMAGE.defaultBounds(g.guiWidth(), g.guiHeight());
        HudLayout.begin(g, HudElement.MELEE_DAMAGE);
        draw(g, current, (int) bounds.x(), (int) bounds.y());
        HudLayout.end(g);
    }

    private static HudCard.Snapshot build(MeleeDamageTracker.Snapshot snapshot,
                                          SBSConfig.DamageAttributionSettings cfg) {
        Font font = Minecraft.getInstance().font;
        String title = "Melee Damage";
        String headline = DamageFormat.compact(snapshot.last()) + (snapshot.lastCrit() ? " ✧" : "");

        List<HudCard.Line> rows = new ArrayList<>(5);
        rows.add(HudCard.Line.of(font, title, SBSTheme.ACCENT));
        rows.add(HudCard.Line.of(font, headline, snapshot.lastCrit() ? CRIT_COLOR : SBSTheme.ACCENT_BRIGHT));
        if (cfg.meleeShowMax) {
            rows.add(HudCard.Line.of(font, "Best", SBSTheme.TEXT_MUTED,
                    DamageFormat.compact(snapshot.best()), SBSTheme.TEXT));
        }
        if (cfg.meleeShowDps) {
            rows.add(HudCard.Line.of(font, "DPS", SBSTheme.TEXT_MUTED,
                    DamageFormat.compact(snapshot.dps()), SBSTheme.TEXT));
            rows.add(HudCard.Line.of(font, "Hits", SBSTheme.TEXT_MUTED,
                    String.valueOf(snapshot.count()), SBSTheme.TEXT));
        }

        int contentW = 0;
        for (HudCard.Line row : rows) {
            contentW = Math.max(contentW, row.right() == null ? row.leftWidth() : row.width(COLUMN_GAP));
        }
        int lineH = font.lineHeight + LINE_GAP;
        int width = Math.max(MIN_W, PAD * 2 + contentW);
        int height = PAD * 2 + lineH * rows.size() - LINE_GAP;
        return new HudCard.Snapshot(List.copyOf(rows), width, height, null);
    }

    private static void draw(GuiGraphicsExtractor g, HudCard.Snapshot card, int x, int y) {
        Font font = Minecraft.getInstance().font;
        HudLayout.measure(HudElement.MELEE_DAMAGE, x, y, card.width(), card.height());

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
