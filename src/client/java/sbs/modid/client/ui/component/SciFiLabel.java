/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.component;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.List;

/**
 * A non-interactive description row for the module settings pages: muted text, left-aligned to the
 * settings column like every other row label, and word-wrapped <b>inside</b> the row bounds (lines
 * that would not fit the row height are dropped) – long descriptions can never bleed past the
 * panel the way the old centered single-line labels did.
 */
public final class SciFiLabel extends AbstractWidget {

    public SciFiLabel(int x, int y, int width, int height, Component text) {
        super(x, y, width, height, text);
        this.active = false; // display only – never focusable / clickable
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        Font font = Minecraft.getInstance().font;
        List<FormattedCharSequence> lines = font.split(getMessage(), Math.max(10, getWidth() - 4));
        int maxLines = Math.max(1, getHeight() / font.lineHeight);
        if (lines.size() > maxLines) {
            lines = lines.subList(0, maxLines);
        }
        int textY = getY() + (getHeight() - lines.size() * font.lineHeight) / 2 + 1;
        for (FormattedCharSequence line : lines) {
            g.text(font, line, getX() + 2, textY, SBSTheme.TEXT_MUTED);
            textY += font.lineHeight;
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
