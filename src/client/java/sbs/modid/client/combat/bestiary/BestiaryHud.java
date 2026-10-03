/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.bestiary;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;


/**
 * The Bestiary Tracker HUD card: for the pinned mob, the <b>kills remaining to max</b> as the headline,
 * plus the current/needed totals and a progress bar. Falls back to a "open the Bestiary menu" prompt
 * while the mob has not been read from the menu yet. Movable / scalable via the GUI editor.
 */
public final class BestiaryHud {

    private static final int PAD = 6;
    private static final int LINE_GAP = 3;
    private static final int BAR_H = 5;
    private static final int MIN_W = 150;

    private static final int MAXED_COLOR = 0xFF57D977;

    private BestiaryHud() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        var cfg = ConfigManager.getInstance().get().bestiary;
        if (!cfg.enabled || Minecraft.getInstance().player == null
                || HudLayout.isHidden(HudElement.BESTIARY_TRACKER)) {
            return;
        }
        String pinnedName = BestiaryTracker.getInstance().pinnedName();
        if (pinnedName.isEmpty()) {
            return; // nothing pinned – the card stays hidden
        }
        HudElement.Bounds bounds = HudElement.BESTIARY_TRACKER.defaultBounds(g.guiWidth(), g.guiHeight());
        HudLayout.begin(g, HudElement.BESTIARY_TRACKER);
        draw(g, BestiaryTracker.getInstance().pinned(), pinnedName, (int) bounds.x(), (int) bounds.y());
        HudLayout.end(g);
    }

    private static void draw(GuiGraphicsExtractor g, BestiaryTracker.Mob mob, String pinnedName, int x, int y) {
        Font font = Minecraft.getInstance().font;

        String title = "Bestiary: " + (mob != null ? mob.name() : pinnedName);
        String headline;
        String detail;
        int headlineColor;
        double fraction;

        if (mob == null) {
            headline = "not loaded";
            detail = "open the Bestiary menu";
            headlineColor = SBSTheme.TEXT_MUTED;
            fraction = 0;
        } else if (mob.maxed() || mob.remaining() == 0) {
            headline = "MAX ✔";
            detail = mob.maxKills() > 0 ? format(mob.maxKills()) + " kills" : format(mob.kills()) + " kills";
            headlineColor = MAXED_COLOR;
            fraction = 1;
        } else if (mob.remaining() < 0) {
            // Total unknown (lore had no max) – show what we have and prompt a refresh.
            headline = format(mob.kills()) + " kills";
            detail = "re-open the Bestiary for the max";
            headlineColor = SBSTheme.TEXT;
            fraction = 0;
        } else {
            headline = format(mob.remaining()) + " kills to max";
            detail = format(mob.kills()) + " / " + format(mob.maxKills());
            headlineColor = SBSTheme.ACCENT_BRIGHT;
            fraction = mob.maxKills() > 0 ? Math.min(1.0, mob.kills() / (double) mob.maxKills()) : 0;
        }

        int contentW = Math.max(font.width(title), Math.max(font.width(headline), font.width(detail)));
        int width = Math.max(MIN_W, PAD * 2 + contentW);
        int lineH = font.lineHeight + LINE_GAP;
        int height = PAD * 2 + lineH * 2 + BAR_H + LINE_GAP + font.lineHeight;
        HudLayout.measure(HudElement.BESTIARY_TRACKER, x, y, width, height);

        HudCard.draw(g, x, y, width, height);

        int ix = x + PAD;
        int iy = y + PAD;
        g.text(font, Component.literal(title), ix, iy, SBSTheme.ACCENT);
        iy += lineH;
        g.text(font, Component.literal(headline), ix, iy, headlineColor);
        iy += lineH;

        int barW = width - PAD * 2;
        SciFiRender.roundedRect(g, ix, iy, barW, BAR_H, 2, SBSTheme.CARD_BG_DISABLED);
        int fill = (int) Math.round(barW * fraction);
        if (fill > 0) {
            SciFiRender.roundedRect(g, ix, iy, fill, BAR_H, 2, fraction >= 1 ? MAXED_COLOR : SBSTheme.ACCENT);
        }
        iy += BAR_H + LINE_GAP;

        g.text(font, Component.literal(detail), ix, iy, SBSTheme.TEXT_MUTED);
    }

    private static String format(long value) {
        return sbs.modid.client.core.util.NumberDisplay.format(value);
    }
}
