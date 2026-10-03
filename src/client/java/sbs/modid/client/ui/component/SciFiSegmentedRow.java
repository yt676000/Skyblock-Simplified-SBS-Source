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
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.ui.render.RowText;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * A settings row whose value is a choice of two to five named modes: the label on the left, every
 * option visible on the right as a {@link SciFiSegmentedSwitch}, one of them lit.
 *
 * <p><b>Why this exists.</b> {@code ui/AGENTS.md} requires a segmented switch for two to five
 * exclusive values and bans the cycle button that hides them - but until now no settings <i>row</i>
 * could draw one, so every such setting on a config page had to reach for a dropdown (which hides
 * the choices behind a click) or a boolean whose two modes had to be squeezed into on and off. The
 * rule was unenforceable for the place it most applies. This is the missing row.
 *
 * <p>The anatomy is {@link SciFiToggleButton}'s, deliberately: the same card, the same left-aligned
 * label that yields with {@link RowText#fit} rather than running under the control, and the control
 * hard against the right edge - so a column mixing toggles and mode rows has one right-hand edge.
 *
 * <p>Holds no state of its own. The selection is read live and a click reports an index, so the
 * backing setting stays the single source of truth.
 */
public class SciFiSegmentedRow extends AbstractWidget {

    private final Component label;
    private final SciFiSegmentedSwitch control;

    /**
     * @param options the mode labels, left to right; their indices are what {@code selected} returns
     *                and {@code onSelect} receives
     */
    public SciFiSegmentedRow(int x, int y, int width, int height, Component label,
                             List<String> options, IntSupplier selected, IntConsumer onSelect) {
        super(x, y, width, height, label);
        this.label = label;
        int controlWidth = SciFiSegmentedSwitch.widthFor(options);
        this.control = new SciFiSegmentedSwitch(x + width - controlWidth - 6, y + 2, height - 4,
                options, selected, onSelect);
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY,
                                            float partialTick) {
        var font = Minecraft.getInstance().font;
        int x = getX();
        int y = getY();
        int w = getWidth();
        int h = getHeight();

        boolean hovered = isHoveredOrFocused() && this.active;
        SciFiRender.roundedRectWithBorder(g, x, y, w, h, SBSTheme.CORNER_RADIUS,
                hovered ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                hovered ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);

        // The label stops before the switch rather than running underneath it.
        int textY = y + (h - font.lineHeight) / 2;
        g.text(font, RowText.fit(font, label, control.getX() - (x + 8) - 6), x + 8, textY,
                SBSTheme.TEXT);
        control.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    /**
     * Only clicks that land on the switch itself do anything.
     *
     * <p>The rest of the card is inert on purpose. A row is also the thing you hover to read its
     * description and click past to reach the favourite star beside it, and a control that claimed
     * the whole width would change the value on both - the same defect the full-width slider had.
     */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        return control.mouseClicked(event, doubled);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
