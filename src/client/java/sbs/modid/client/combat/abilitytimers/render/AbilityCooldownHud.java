/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.abilitytimers.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.combat.abilitytimers.logic.AbilityCooldownTracker;
import sbs.modid.client.combat.abilitytimers.logic.TacTimerTracker;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.List;
import java.util.Locale;

/**
 * The death-save cooldown card: one row per tracked ability (Bonzo's Mask, Spirit Mask, Phoenix),
 * counting down to ready. Each row wears its ability's colour while it is on cooldown and turns
 * green the moment it is back. Self-measuring and movable via the GUI editor.
 *
 * <p>A row only exists once that ability has actually fired this session - an untouched Bonzo Mask
 * shows nothing rather than a permanent "READY" that trains you to ignore the card.
 */
public final class AbilityCooldownHud {

    private static final int PAD = 6;
    private static final int LINE_GAP = 3;
    private static final int MIN_W = 108;
    private static final int READY_COLOR = 0xFF66E27A;
    private static final int TAC_COLOR = 0xFFFFC040;
    /** {@code tacDisplay} values; 2 is "both". */
    private static final int DISPLAY_CARD = 0;
    private static final int DISPLAY_CROSSHAIR = 1;
    /** Pixels below the screen centre - clear of the crosshair, above any subtitle. */
    private static final int CROSSHAIR_OFFSET = 12;

    private static String tacText;
    private static Component tacLine;

    private AbilityCooldownHud() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        if (Minecraft.getInstance().player == null) {
            return;
        }
        SBSConfig.AbilityTimerSettings cfg = ConfigManager.getInstance().get().abilityTimers;
        // The Tac Timer is not a death save and has its own switch - its row shows with the
        // death-save master off.
        String tac = TacTimerTracker.getInstance().text();
        Component tacLine = tacComponent(tac);
        if (tacLine != null && cfg.tacDisplay != DISPLAY_CARD) {
            drawUnderCrosshair(g, tacLine);
        }
        String cardTac = cfg.tacDisplay != DISPLAY_CROSSHAIR ? tac : null;
        if (HudLayout.isHidden(HudElement.ABILITY_TIMERS)) {
            return;
        }
        List<AbilityCooldownTracker.Entry> entries = cfg.enabled
                ? AbilityCooldownTracker.getInstance().entries() : List.of();
        if (entries.isEmpty() && cardTac == null) {
            return;
        }
        HudElement.Bounds bounds = HudElement.ABILITY_TIMERS.defaultBounds(g.guiWidth(), g.guiHeight());
        HudLayout.begin(g, HudElement.ABILITY_TIMERS);
        draw(g, entries, cardTac == null ? null : tacLine, (int) bounds.x(), (int) bounds.y());
        HudLayout.end(g);
    }

    /** The Tac countdown, centred a little below the crosshair. */
    private static void drawUnderCrosshair(GuiGraphicsExtractor g, Component line) {
        Font font = Minecraft.getInstance().font;
        g.centeredText(font, line, g.guiWidth() / 2, g.guiHeight() / 2 + CROSSHAIR_OFFSET, TAC_COLOR);
    }

    /** One component per text change (10 Hz at most), not one per frame. */
    private static Component tacComponent(String text) {
        if (text == null) {
            return null;
        }
        if (!text.equals(tacText)) {
            tacText = text;
            tacLine = Component.literal(text);
        }
        return tacLine;
    }

    private static void draw(GuiGraphicsExtractor g, List<AbilityCooldownTracker.Entry> entries,
                             Component tac, int x, int y) {
        Font font = Minecraft.getInstance().font;

        int contentW = tac == null ? 0 : font.width(tac);
        for (AbilityCooldownTracker.Entry entry : entries) {
            contentW = Math.max(contentW, font.width(text(entry)));
        }
        int rows = entries.size() + (tac == null ? 0 : 1);
        int width = Math.max(MIN_W, PAD * 2 + contentW);
        int lineH = font.lineHeight + LINE_GAP;
        int height = PAD * 2 + lineH * rows - LINE_GAP;
        HudLayout.measure(HudElement.ABILITY_TIMERS, x, y, width, height);

        HudCard.draw(g, x, y, width, height);

        int ix = x + PAD;
        int iy = y + PAD;
        for (AbilityCooldownTracker.Entry entry : entries) {
            g.text(font, Component.literal(text(entry)), ix, iy,
                    entry.ready() ? READY_COLOR : entry.ability().color());
            iy += lineH;
        }
        if (tac != null) {
            g.text(font, tac, ix, iy, TAC_COLOR);
        }
    }

    private static String text(AbilityCooldownTracker.Entry entry) {
        return entry.ability().label() + "  " + (entry.ready() ? "READY" : time(entry.secondsLeft()));
    }

    /** Seconds under a minute, {@code m:ss} above - Bonzo's minutes read badly as "173s". */
    private static String time(int seconds) {
        return seconds < 60
                ? seconds + "s"
                : String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }
}
