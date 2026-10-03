/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.component;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.function.BooleanSupplier;

/**
 * A {@link SciFiButton} that shows whether it is the currently selected entry of a tab strip:
 * the selected tab gets the accent border, the raised card fill and an accent underline, so the
 * active page is readable at a glance without opening anything.
 *
 * <p>Used for the Profile Viewer's quick-access page tabs, where every page is one click away
 * instead of cycling through a "next page" button.
 */
public class SciFiTabButton extends SciFiButton {

    private final BooleanSupplier selected;

    public SciFiTabButton(int x, int y, int width, int height, Component message,
                          BooleanSupplier selected, Runnable action) {
        super(x, y, width, height, message, action);
        this.selected = selected;
    }

    private boolean isSelected() {
        return selected != null && selected.getAsBoolean();
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        if (!isSelected()) {
            super.extractContents(g, mouseX, mouseY, partialTick);
            return;
        }
        var font = Minecraft.getInstance().font;
        int x = getX();
        int y = getY();
        int w = getWidth();
        int h = getHeight();

        SciFiRender.roundedRectWithBorder(g, x, y, w, h, SBSTheme.CORNER_RADIUS,
                SBSTheme.CARD_BG_HOVER, SBSTheme.ACCENT_BRIGHT);
        // Accent underline marks the active tab even when another tab is hovered.
        g.fill(x + 3, y + h - 3, x + w - 3, y + h - 1, SBSTheme.ACCENT);
        g.centeredText(font, getMessage(), x + w / 2, y + (h - font.lineHeight) / 2 - 1,
                SBSTheme.ACCENT_BRIGHT);
    }
}
