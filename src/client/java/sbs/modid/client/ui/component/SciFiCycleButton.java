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

import java.util.function.Supplier;

/**
 * A themed multi-option setting row: a left-aligned label and a right-aligned, accent-colored
 * value that advances on click.
 *
 * <p>Built like {@link SciFiButton} / {@link SciFiToggleButton} (extends {@link AbstractButton},
 * overrides the {@code extractContents} draw hook) so it shares the card look and all click /
 * focus handling. Clicking runs {@code onCycle} (which advances the backing setting) and the
 * value is read live through {@code value}, so no manual refresh is needed.
 */
public class SciFiCycleButton extends AbstractButton {

    private final Component label;
    private final Supplier<Component> value;
    private final Runnable onCycle;

    public SciFiCycleButton(int x, int y, int width, int height,
                            Component label, Supplier<Component> value, Runnable onCycle) {
        super(x, y, width, height, label);
        this.label = label;
        this.value = value;
        this.onCycle = onCycle;
    }

    @Override
    public void onPress(InputWithModifiers input) {
        if (onCycle != null) {
            onCycle.run();
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

        Component current = value != null ? value.get() : Component.literal("");
        int valueW = font.width(current.getString());
        // The label yields to the value: the two are drawn from opposite edges and would otherwise
        // meet in the middle of a narrow row.
        g.text(font, RowText.fit(font, label, w - 16 - valueW - 6),
                x + 8, textY, SBSTheme.TEXT);
        g.text(font, current, x + w - 8 - valueW, textY, SBSTheme.ACCENT);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
