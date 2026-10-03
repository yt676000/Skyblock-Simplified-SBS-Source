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
import sbs.modid.client.core.config.ConfigManager;
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

    private AbilityCooldownHud() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        if (!ConfigManager.getInstance().get().abilityTimers.enabled
                || Minecraft.getInstance().player == null
                || HudLayout.isHidden(HudElement.ABILITY_TIMERS)) {
            return;
        }
        List<AbilityCooldownTracker.Entry> entries = AbilityCooldownTracker.getInstance().entries();
        if (entries.isEmpty()) {
            return;
        }
        HudElement.Bounds bounds = HudElement.ABILITY_TIMERS.defaultBounds(g.guiWidth(), g.guiHeight());
        HudLayout.begin(g, HudElement.ABILITY_TIMERS);
        draw(g, entries, (int) bounds.x(), (int) bounds.y());
        HudLayout.end(g);
    }

    private static void draw(GuiGraphicsExtractor g, List<AbilityCooldownTracker.Entry> entries,
                             int x, int y) {
        Font font = Minecraft.getInstance().font;

        int contentW = 0;
        for (AbilityCooldownTracker.Entry entry : entries) {
            contentW = Math.max(contentW, font.width(text(entry)));
        }
        int width = Math.max(MIN_W, PAD * 2 + contentW);
        int lineH = font.lineHeight + LINE_GAP;
        int height = PAD * 2 + lineH * entries.size() - LINE_GAP;
        HudLayout.measure(HudElement.ABILITY_TIMERS, x, y, width, height);

        HudCard.draw(g, x, y, width, height);

        int ix = x + PAD;
        int iy = y + PAD;
        for (AbilityCooldownTracker.Entry entry : entries) {
            g.text(font, Component.literal(text(entry)), ix, iy,
                    entry.ready() ? READY_COLOR : entry.ability().color());
            iy += lineH;
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
