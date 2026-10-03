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
import sbs.modid.client.combat.damage.logic.MeleeDamageTracker;
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

    private MeleeDamageHud() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.DamageAttributionSettings cfg = ConfigManager.getInstance().get().damageAttribution;
        if (!cfg.meleeHud || Minecraft.getInstance().player == null
                || HudLayout.isHidden(HudElement.MELEE_DAMAGE)) {
            return;
        }
        MeleeDamageTracker.Snapshot snapshot = MeleeDamageTracker.getInstance().snapshot();
        if (snapshot == null) {
            return; // nothing hit recently
        }
        HudElement.Bounds bounds = HudElement.MELEE_DAMAGE.defaultBounds(g.guiWidth(), g.guiHeight());
        HudLayout.begin(g, HudElement.MELEE_DAMAGE);
        draw(g, snapshot, cfg, (int) bounds.x(), (int) bounds.y());
        HudLayout.end(g);
    }

    private static void draw(GuiGraphicsExtractor g, MeleeDamageTracker.Snapshot snapshot,
                             SBSConfig.DamageAttributionSettings cfg, int x, int y) {
        Font font = Minecraft.getInstance().font;
        String title = "Melee Damage";
        String headline = DamageFormat.compact(snapshot.last()) + (snapshot.lastCrit() ? " ✧" : "");

        List<String[]> rows = new ArrayList<>(2);
        if (cfg.meleeShowMax) {
            rows.add(new String[] {"Best", DamageFormat.compact(snapshot.best())});
        }
        if (cfg.meleeShowDps) {
            rows.add(new String[] {"DPS", DamageFormat.compact(snapshot.dps())});
            rows.add(new String[] {"Hits", String.valueOf(snapshot.count())});
        }

        int contentW = Math.max(font.width(title), font.width(headline));
        for (String[] row : rows) {
            contentW = Math.max(contentW, font.width(row[0]) + COLUMN_GAP + font.width(row[1]));
        }

        int lineH = font.lineHeight + LINE_GAP;
        int width = Math.max(MIN_W, PAD * 2 + contentW);
        int height = PAD * 2 + lineH * (2 + rows.size()) - LINE_GAP;
        HudLayout.measure(HudElement.MELEE_DAMAGE, x, y, width, height);

        HudCard.draw(g, x, y, width, height);

        int ix = x + PAD;
        int rightEdge = x + width - PAD;
        int iy = y + PAD;
        g.text(font, Component.literal(title), ix, iy, SBSTheme.ACCENT);
        iy += lineH;
        g.text(font, Component.literal(headline), ix, iy,
                snapshot.lastCrit() ? CRIT_COLOR : SBSTheme.ACCENT_BRIGHT);
        iy += lineH;

        for (String[] row : rows) {
            g.text(font, Component.literal(row[0]), ix, iy, SBSTheme.TEXT_MUTED);
            g.text(font, Component.literal(row[1]), rightEdge - font.width(row[1]), iy, SBSTheme.TEXT);
            iy += lineH;
        }
    }
}
