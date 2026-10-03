/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.helper.build.logic.Freecam;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.render.RowText;
import sbs.modid.client.ui.theme.SBSTheme;

/**
 * The "Freecam: Build / Cinematic" chip at the top of the screen, with the camera's coordinates and,
 * briefly, the speed. Its own draw rather than a Build HUD line, so it shows with Build Tools off and
 * on a hidden HUD (there for the first 2 s only - {@link Freecam#indicator} decides).
 */
public final class FreecamIndicator {

    private static final int PAD = 4;

    private FreecamIndicator() {
    }

    public static void render(GuiGraphicsExtractor g) {
        String text = Freecam.indicator();
        if (text == null) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        int width = Math.min(g.guiWidth() - 2 * SBSTheme.SCREEN_MARGIN, font.width(text) + PAD * 2);
        int height = font.lineHeight + PAD * 2;
        int x = (g.guiWidth() - width) / 2;
        int y = SBSTheme.SCREEN_MARGIN;
        HudCard.draw(g, x, y, width, height);
        g.text(font, Component.literal(RowText.fit(font, text, width - PAD * 2)), x + PAD, y + PAD,
                SBSTheme.ACCENT_BRIGHT);
    }
}
