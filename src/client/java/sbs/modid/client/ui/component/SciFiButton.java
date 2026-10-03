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
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

/**
 * A themed Sci-Fi button.
 *
 * <p>Extends {@link AbstractButton} so it inherits all click / key / narration
 * handling, and only overrides {@code extractContents} – the (non-final) draw hook
 * in this version's render system – to paint a rounded, dark-blue card with white
 * accents instead of the vanilla grey button sprite. ({@code extractWidgetRenderState}
 * is {@code final} here and merely delegates to {@code extractContents}, so this
 * fully replaces the default look.)
 *
 * <p>{@link #asLabel()} turns it into a non-interactive text label (used for the
 * page indicator and the "no results" placeholder) with no background.
 */
public class SciFiButton extends AbstractButton {

    private final Runnable action;
    private boolean decorative;

    /** Corner radius; {@code -1} follows the theme, anything else is a per-button override. */
    private int cornerRadius = -1;

    public SciFiButton(int x, int y, int width, int height, Component message, Runnable action) {
        super(x, y, width, height, message);
        this.action = action;
    }

    /** Marks this as a non-interactive label (no background, muted centered text). */
    public SciFiButton asLabel() {
        this.decorative = true;
        this.active = false;
        return this;
    }

    /**
     * Overrides the theme's corner radius for this button alone, or restores it with {@code -1}.
     * Used by the pause-menu editor, where each button's shape is set individually.
     */
    public void setCornerRadius(int radius) {
        this.cornerRadius = radius;
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        var font = Minecraft.getInstance().font;
        int x = getX();
        int y = getY();
        int w = getWidth();
        int h = getHeight();
        int textY = y + (h - font.lineHeight) / 2;

        if (decorative) {
            g.centeredText(font, getMessage(), x + w / 2, textY, SBSTheme.TEXT_MUTED);
            return;
        }

        boolean hovered = isHoveredOrFocused() && this.active;
        int bg = !this.active
                ? SBSTheme.CARD_BG_DISABLED
                : (hovered ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG);
        int border = hovered ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER;

        SciFiRender.roundedRectWithBorder(g, x, y, w, h,
                cornerRadius >= 0 ? cornerRadius : SBSTheme.CORNER_RADIUS, bg, border);

        // Accent tab on the left edge when highlighted.
        if (hovered) {
            g.fill(x + 2, y + 3, x + 4, y + h - 3, SBSTheme.ACCENT);
        }

        int textColor = this.active ? SBSTheme.TEXT : SBSTheme.TEXT_MUTED;
        g.centeredText(font, getMessage(), x + w / 2, textY, textColor);
    }

    @Override
    public void onPress(InputWithModifiers input) {
        if (action != null) {
            action.run();
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
