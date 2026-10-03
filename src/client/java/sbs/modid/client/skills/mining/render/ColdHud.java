/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig.MiningHelpersSettings;
import sbs.modid.client.skills.mining.logic.ColdAlarm;
import sbs.modid.client.skills.mining.logic.ColdTracker;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.theme.SBSTheme;

/** The "Cold: N / cap" card. Drawn only in a Glacite zone with a fresh reading. */
public final class ColdHud {

    private static final int PAD = 5;

    private ColdHud() {
    }

    public static void render(GuiGraphicsExtractor g) {
        MiningHelpersSettings settings = ConfigManager.getInstance().get().miningHelpers;
        if (!settings.enabled || !settings.coldCard || Minecraft.getInstance().player == null
                || HudLayout.isHidden(HudElement.GLACITE_COLD)) {
            return;
        }
        int cold = ColdTracker.getInstance().cold();
        if (cold < 0) {
            return;   // unknown: no card rather than a guessed number
        }
        HudElement.Bounds bounds = HudElement.GLACITE_COLD.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = (int) bounds.x();
        int y = (int) bounds.y();
        HudLayout.begin(g, HudElement.GLACITE_COLD);
        Font font = Minecraft.getInstance().font;
        String label = "Cold ";
        String value = cold + " / " + settings.coldCap;
        int width = PAD * 2 + font.width(label) + font.width(value);
        int height = PAD * 2 + font.lineHeight - 1;
        HudLayout.measure(HudElement.GLACITE_COLD, x, y, width, height);
        HudCard.draw(g, x, y, width, height);
        g.text(font, Component.literal(label), x + PAD, y + PAD, SBSTheme.TEXT_MUTED);
        g.text(font, Component.literal(value), x + PAD + font.width(label), y + PAD,
                color(cold, settings));
        HudLayout.end(g);
    }

    /** Green while far off, amber from half way, red from the warning threshold. */
    private static int color(int cold, MiningHelpersSettings settings) {
        int threshold = ColdAlarm.threshold(settings.coldCap, settings.coldWarnPercent);
        if (cold >= threshold) {
            return SBSTheme.WARN;
        }
        return cold * 2 >= settings.coldCap ? 0xFFF0B030 : 0xFF6FD08C;
    }
}
