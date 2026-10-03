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
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;
import sbs.modid.client.ui.render.RowText;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.function.BooleanSupplier;

/**
 * A themed on/off settings toggle – a reusable building block for module setting screens.
 *
 * <p>Built like {@link SciFiButton} (extends {@link AbstractButton}, overrides the
 * non-final {@code extractContents} draw hook) so it shares the dark-blue card look and all
 * click / focus handling. It renders a left-aligned label with an {@code ON}/{@code OFF}
 * pill on the right; clicking it runs {@code onToggle} (which flips the backing setting) and
 * the pill reflects the live value read through {@code state}, so no manual refresh is
 * needed.
 */
public class SciFiToggleButton extends AbstractButton {

    private final Component label;
    private final BooleanSupplier state;
    private final Runnable onToggle;

    public SciFiToggleButton(int x, int y, int width, int height,
                             Component label, BooleanSupplier state, Runnable onToggle) {
        super(x, y, width, height, label);
        this.label = label;
        this.state = state;
        this.onToggle = onToggle;
    }

    @Override
    public void onPress(InputWithModifiers input) {
        if (onToggle != null) {
            onToggle.run();
        }
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        var font = Minecraft.getInstance().font;
        int x = getX();
        int y = getY();
        int w = getWidth();
        int h = getHeight();
        int textY = y + (h - font.lineHeight) / 2;

        boolean hovered = isHoveredOrFocused() && this.active;
        int bg = hovered ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG;
        int border = hovered ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER;
        SciFiRender.roundedRectWithBorder(g, x, y, w, h, SBSTheme.CORNER_RADIUS, bg, border);

        boolean on = state != null && state.getAsBoolean();
        Component pill = Component.literal(on ? "ON" : "OFF");
        int pillW = 28;
        int pillH = h - 6;
        int pillX = x + w - pillW - 6;
        int pillY = y + 3;
        // The label stops before the ON/OFF pill instead of running underneath it.
        g.text(font, RowText.fit(font, label, pillX - (x + 8) - 6), x + 8, textY, SBSTheme.TEXT);
        int pillFrame = on ? SBSTheme.TOGGLE_ON : SBSTheme.CARD_BORDER;
        int pillText = on ? SBSTheme.TOGGLE_ON : SBSTheme.TEXT_MUTED;
        SciFiRender.roundedRectWithBorder(g, pillX, pillY, pillW, pillH, 3, SBSTheme.CARD_BG_DISABLED, pillFrame);
        g.centeredText(font, pill, pillX + pillW / 2, textY, pillText);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
