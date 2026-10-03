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
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * A segmented switch: two or more labels sharing one frame, of which exactly one is active – the
 * "double switch button" next to the config search bar, where the mode has to be readable without
 * clicking anything.
 *
 * <p>Deliberately not a {@link SciFiCycleButton}: a cycle button shows the current value but hides
 * what else there is, and a two-value choice that has to be <i>seen</i> (which mode is the search
 * in?) reads far better as both options side by side with one of them lit. The active segment is a
 * solid accent chip inside a search-field frame – the whole control is one search-bar-height row, so
 * it sits next to the field as if it were part of it.
 *
 * <p>The widget owns no state: the selection is read live through {@code selected} and a click
 * reports the clicked index to {@code onSelect}, so the backing value stays the single source of
 * truth (clicking the active segment reports it again – the caller decides that is a no-op).
 */
public final class SciFiSegmentedSwitch extends AbstractWidget {

    /** Text inset on each side of a segment's label. */
    private static final int SEGMENT_PADDING = 6;

    private final List<Component> options;
    private final IntSupplier selected;
    private final IntConsumer onSelect;

    /**
     * @param options the segment labels, left to right; their indices are what {@code selected}
     *                returns and {@code onSelect} receives
     */
    public SciFiSegmentedSwitch(int x, int y, int height, List<String> options,
                                IntSupplier selected, IntConsumer onSelect) {
        super(x, y, widthFor(options), height, Component.literal(String.join(" / ", options)));
        List<Component> labels = new ArrayList<>(options.size());
        for (String option : options) {
            labels.add(Component.literal(option));
        }
        this.options = List.copyOf(labels);
        this.selected = selected;
        this.onSelect = onSelect;
    }

    /**
     * The width this control needs for the given labels, so the caller can lay the rest of the row
     * out around it <b>before</b> the widget exists.
     */
    public static int widthFor(List<String> options) {
        Font font = Minecraft.getInstance().font;
        int width = 2;   // the frame's own two pixels
        for (String option : options) {
            width += font.width(option) + SEGMENT_PADDING * 2;
        }
        return width;
    }

    private int segmentWidth(Font font, int index) {
        return font.width(options.get(index).getString()) + SEGMENT_PADDING * 2;
    }

    /** The segment under {@code mouseX}, or {@code -1} outside every one of them. */
    private int segmentAt(double mouseX) {
        Font font = Minecraft.getInstance().font;
        int segX = getX() + 1;
        for (int i = 0; i < options.size(); i++) {
            int segW = segmentWidth(font, i);
            if (mouseX >= segX && mouseX < segX + segW) {
                return i;
            }
            segX += segW;
        }
        return -1;
    }

    private int current() {
        return selected == null ? 0 : selected.getAsInt();
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        Font font = Minecraft.getInstance().font;
        int x = getX();
        int y = getY();
        int h = getHeight();

        // The frame is the search field's frame: same fill, same border, same radius, same height, so
        // the switch and the field read as one row rather than two separate controls.
        SciFiRender.roundedRectWithBorder(g, x, y, getWidth(), h, SBSTheme.CORNER_RADIUS,
                SBSTheme.SEARCH_FILL, isMouseOver(mouseX, mouseY)
                        ? SBSTheme.ACCENT : SBSTheme.CARD_BORDER);

        int active = current();
        int textY = y + (h - font.lineHeight) / 2;
        int chipRadius = Math.max(2, SBSTheme.CORNER_RADIUS - 2);
        int segX = x + 1;
        for (int i = 0; i < options.size(); i++) {
            int segW = segmentWidth(font, i);
            boolean selectedSegment = i == active;
            boolean hovered = mouseX >= segX && mouseX < segX + segW
                    && mouseY >= y && mouseY < y + h;

            int color;
            if (selectedSegment) {
                // A solid accent chip: at this size a 1px outline is the difference between "selected"
                // and "not selected" only if you go looking for it, and the mode has to be readable at
                // a glance. Inset on all four sides so the frame stays visible around it.
                // The label keeps the theme's own text colour - the chip already carries the "this one
                // is active" signal, so recolouring the text on top of it only breaks the one thing the
                // player is allowed to pick.
                SciFiRender.roundedRect(g, segX + 1, y + 2, segW - 2, h - 4, chipRadius, SBSTheme.ACCENT);
                color = SBSTheme.TEXT;
            } else {
                if (hovered) {
                    SciFiRender.roundedRect(g, segX + 1, y + 2, segW - 2, h - 4, chipRadius,
                            SBSTheme.CARD_BG_HOVER);
                }
                color = hovered ? SBSTheme.TEXT : SBSTheme.TEXT_MUTED;
            }
            g.centeredText(font, options.get(i), segX + segW / 2, textY, color);
            segX += segW;
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (!this.visible || !this.active || event.button() != 0
                || !isMouseOver(event.x(), event.y())) {
            return false;
        }
        int index = segmentAt(event.x());
        if (index >= 0 && onSelect != null) {
            onSelect.accept(index);
        }
        // The click landed on the control either way; swallowing it keeps the gap between two
        // segments from falling through to whatever is behind the switch.
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        int active = current();
        Component value = active >= 0 && active < options.size() ? options.get(active) : Component.empty();
        output.add(NarratedElementType.TITLE, Component.literal(getMessage().getString() + ": ")
                .append(value));
    }
}
