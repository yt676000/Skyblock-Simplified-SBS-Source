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
import sbs.modid.client.core.render.OverlayColor;
import sbs.modid.client.ui.render.RowText;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * A colour row for a settings page: label on the left, a swatch of the colour actually in use on the
 * right, and the picker one click away.
 *
 * <p>Built like {@link SciFiToggleButton} (an {@link AbstractButton} that only replaces the draw
 * hook), so it inherits the card look and every click / focus behaviour the other rows have.
 *
 * <p>The swatch is what the value <b>means</b>, not what is stored: an unset override reads as
 * {@code Default} and shows the stock colour supplied by {@code fallback}, so a row that was never
 * touched still tells the player which colour that bar is right now.
 */
public class SciFiColorButton extends AbstractButton {

    private final Component label;
    /** The configured {@code RRGGBB}, or empty/invalid while no override is set. */
    private final Supplier<String> hex;
    /** The colour used when nothing is configured - shown so the swatch is never a lie. */
    private final IntSupplier fallback;
    private final Runnable onPress;

    public SciFiColorButton(int x, int y, int width, int height, Component label,
                            Supplier<String> hex, IntSupplier fallback, Runnable onPress) {
        super(x, y, width, height, label);
        this.label = label;
        this.hex = hex;
        this.fallback = fallback;
        this.onPress = onPress;
    }

    @Override
    public void onPress(InputWithModifiers input) {
        if (onPress != null) {
            onPress.run();
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

        Integer rgb = OverlayColor.parseHex(hex == null ? null : hex.get());
        // Alpha is dropped from the fallback: the swatch shows the hue, not the bar's opacity.
        int shown = rgb != null ? rgb : (fallback == null ? 0 : fallback.getAsInt() & 0xFFFFFF);

        int swatchW = 22;
        int swatchH = h - 6;
        int swatchX = x + w - swatchW - 6;
        SciFiRender.roundedRectWithBorder(g, swatchX, y + 3, swatchW, swatchH, 3,
                0xFF000000 | shown, SBSTheme.CARD_BORDER);

        String value = rgb != null ? String.format(java.util.Locale.ROOT, "%06X", rgb) : "Default";
        int valueX = swatchX - 6 - font.width(value);
        // Label last, cut to what the hex value and the swatch left of the row.
        g.text(font, RowText.fit(font, label, valueX - (x + 8) - 6),
                x + 8, textY, SBSTheme.TEXT);
        g.text(font, Component.literal(value), valueX, textY,
                rgb != null ? SBSTheme.TEXT : SBSTheme.TEXT_MUTED);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
