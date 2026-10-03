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
import sbs.modid.client.combat.damage.logic.AbilityDamageTracker;
import sbs.modid.client.combat.damage.model.AbilityDamageMode;
import sbs.modid.client.combat.damage.model.DamageFormat;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;

import java.util.List;

/**
 * The Ability Damage card: the ability-damage lines taken out of chat, listed newest first with the
 * ability name, how many enemies it caught and the damage. Self-hiding – it only exists while
 * {@link AbilityDamageMode#HUD} is picked and a hit is still inside its hold window. Movable and
 * scalable through the GUI editor like every other SBS overlay.
 */
public final class AbilityDamageHud {

    private static final int PAD = 6;
    private static final int LINE_GAP = 2;
    private static final int MIN_W = 120;

    /** Gap between the ability name column and the right-aligned damage column. */
    private static final int COLUMN_GAP = 10;

    private AbilityDamageHud() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.AbilityDamageSettings cfg = ConfigManager.getInstance().get().abilityDamage;
        if (cfg.mode != AbilityDamageMode.HUD || Minecraft.getInstance().player == null
                || HudLayout.isHidden(HudElement.ABILITY_DAMAGE)) {
            return;
        }
        List<AbilityDamageTracker.Hit> hits = AbilityDamageTracker.getInstance().recent();
        if (hits.isEmpty()) {
            return; // nothing hit recently – the card stays away entirely
        }
        HudElement.Bounds bounds = HudElement.ABILITY_DAMAGE.defaultBounds(g.guiWidth(), g.guiHeight());
        HudLayout.begin(g, HudElement.ABILITY_DAMAGE);
        draw(g, hits, cfg, (int) bounds.x(), (int) bounds.y());
        HudLayout.end(g);
    }

    private static void draw(GuiGraphicsExtractor g, List<AbilityDamageTracker.Hit> hits,
                             SBSConfig.AbilityDamageSettings cfg, int x, int y) {
        Font font = Minecraft.getInstance().font;
        String title = "Ability Damage";

        // Build the two columns first, so the card can size itself to the widest real row.
        int rows = hits.size();
        String[] left = new String[rows];
        String[] right = new String[rows];
        double total = 0;
        for (int i = 0; i < rows; i++) {
            AbilityDamageTracker.Hit hit = hits.get(i);
            left[i] = hit.enemies() > 1 ? hit.ability() + " §8x" + hit.enemies() : hit.ability();
            right[i] = DamageFormat.compact(hit.damage());
            total += hit.damage();
        }
        String totalLabel = cfg.hudTotal && rows > 1 ? "Total" : null;
        String totalValue = totalLabel == null ? null : DamageFormat.compact(total);

        int contentW = font.width(title);
        for (int i = 0; i < rows; i++) {
            contentW = Math.max(contentW, font.width(left[i]) + COLUMN_GAP + font.width(right[i]));
        }
        if (totalLabel != null) {
            contentW = Math.max(contentW, font.width(totalLabel) + COLUMN_GAP + font.width(totalValue));
        }

        int lineH = font.lineHeight + LINE_GAP;
        int width = Math.max(MIN_W, PAD * 2 + contentW);
        int height = PAD * 2 + lineH * (rows + 1 + (totalLabel != null ? 1 : 0)) - LINE_GAP;
        HudLayout.measure(HudElement.ABILITY_DAMAGE, x, y, width, height);

        HudCard.draw(g, x, y, width, height);

        int ix = x + PAD;
        int rightEdge = x + width - PAD;
        int iy = y + PAD;
        g.text(font, Component.literal(title), ix, iy, SBSTheme.ACCENT);
        iy += lineH;

        for (int i = 0; i < rows; i++) {
            // The newest hit reads as the live one; older rows fade back into the muted palette.
            int color = i == 0 ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT;
            g.text(font, Component.literal(left[i]), ix, iy, i == 0 ? color : SBSTheme.TEXT_MUTED);
            g.text(font, Component.literal(right[i]), rightEdge - font.width(right[i]), iy, color);
            iy += lineH;
        }

        if (totalLabel != null) {
            g.text(font, Component.literal(totalLabel), ix, iy, SBSTheme.TEXT_MUTED);
            g.text(font, Component.literal(totalValue), rightEdge - font.width(totalValue), iy,
                    SBSTheme.ACCENT);
        }
    }
}
