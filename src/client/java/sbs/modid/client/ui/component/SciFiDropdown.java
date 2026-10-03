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
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A real dropdown: label on the left, current value on the right, and a scrollable list that opens
 * over the rest of the screen when clicked.
 *
 * <p>The existing {@link SciFiCycleButton} is the right control for three or four options, but the
 * island filter has twenty-plus and grows as the player travels – cycling to "The Rift" one click at
 * a time is not a control, it is a punishment.
 *
 * <p>The open list overlaps whatever is beneath it, so callers need no layout space for it. Because
 * widgets render in insertion order, the list is <b>not</b> drawn in this widget's own render pass –
 * later rows would paint over it. Instead the widget only draws its box here and the caller renders
 * the open list last via {@link #renderOverlay}, on top of everything. It closes on pick, on a click
 * outside, or on Escape.
 *
 * <p><b>Last means last for the frame, not last at the moment the pass was registered.</b> A screen
 * that rebuilds its rows appends them to the end of the render order, behind nothing and in front of
 * every pass added before them – so a screen which rebuilds must either call {@link #renderOverlay}
 * from its own render override (the way the config screen does) or lift its overlay pass back to the
 * end after each rebuild (the way the keybind editor does). Registering it once in {@code init} and
 * assuming it stays last is the bug this note exists to prevent; it was one.
 */
public final class SciFiDropdown extends AbstractWidget {

    private static final int ROW_H = 12;
    private static final int MAX_VISIBLE = 8;

    private final String label;
    private final Supplier<List<String>> options;
    private final Supplier<String> value;
    private final Consumer<String> onPick;

    private boolean open;
    private int scroll;
    private Runnable onOpen;

    public SciFiDropdown(int x, int y, int width, int height, String label,
                         Supplier<List<String>> options, Supplier<String> value,
                         Consumer<String> onPick) {
        super(x, y, width, height, Component.literal(label));
        this.label = label;
        this.options = options;
        this.value = value;
        this.onPick = onPick;
    }

    public boolean isOpen() {
        return open;
    }

    public void close() {
        open = false;
    }

    /** Runs when this dropdown opens – used to close sibling dropdowns so only one is ever open. */
    public void setOnOpen(Runnable onOpen) {
        this.onOpen = onOpen;
    }

    /**
     * The box, <b>plus the open list</b>.
     *
     * <p>A screen delivers a click to the child under the cursor, and "under the cursor" is this
     * method. Without the list in it the widget painted on top was not the widget being clicked: a
     * pick landed on whatever row the list happened to cover, toggling a setting the player could not
     * even see. Anything that draws outside its own box has to say so here.
     */
    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        if (super.isMouseOver(mouseX, mouseY)) {
            return true;
        }
        if (!open) {
            return false;
        }
        int y = listY();
        return mouseX >= getX() && mouseX < getX() + getWidth()
                && mouseY >= y && mouseY < y + listHeight();
    }

    /** Height of the popup for the current option count, so callers can hit-test it if needed. */
    private int listHeight() {
        return Math.min(MAX_VISIBLE, Math.max(1, options.get().size())) * ROW_H + 2;
    }

    private int listY() {
        // Prefer opening downwards; flip up when there is no room, so the list is never off-screen.
        int below = getY() + getHeight() + 1;
        int screenH = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        return below + listHeight() <= screenH ? below : getY() - listHeight() - 1;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        var font = Minecraft.getInstance().font;
        boolean hovered = isHovered();
        SciFiRender.roundedRectWithBorder(g, getX(), getY(), getWidth(), getHeight(),
                SBSTheme.CORNER_RADIUS, hovered || open ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                open ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);

        int textY = getY() + (getHeight() - font.lineHeight) / 2;
        g.text(font, Component.literal(label), getX() + 6, textY, SBSTheme.TEXT_MUTED);

        // Value right-aligned with the caret behind it; the label is trimmed against it so the two
        // can never overlap however long an island name gets.
        String current = value.get();
        String caret = open ? " ▴" : " ▾";
        int caretW = font.width(caret);
        int space = getWidth() - 12 - font.width(label) - caretW;
        String shown = RowText.fit(font, current, Math.max(8, space));
        int valueX = getX() + getWidth() - 6 - caretW - font.width(shown);
        g.text(font, Component.literal(shown), valueX, textY, SBSTheme.ACCENT_BRIGHT);
        g.text(font, Component.literal(caret), getX() + getWidth() - 6 - caretW, textY, SBSTheme.TEXT_MUTED);
        // The open list is drawn by renderOverlay (a top-most pass), never here.
    }

    /**
     * Draws the open option list on top of everything. The caller must invoke this <b>after</b> all
     * other widgets have rendered (e.g. from a final overlay widget), so the list is never covered by
     * the rows beneath it. A no-op while the dropdown is closed.
     */
    public void renderOverlay(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (open) {
            drawList(g, Minecraft.getInstance().font, mouseX, mouseY);
        }
    }

    private void drawList(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font,
                          int mouseX, int mouseY) {
        List<String> list = options.get();
        int h = listHeight();
        int y = listY();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, list.size() - MAX_VISIBLE)));

        // Opaque, whatever the active style left in the colour: the list hangs over the rows behind
        // it, and a body at the styles' usual 0xE0-0xF0 alpha lets those rows read straight through
        // the options - which looks exactly like a list drawn in the background.
        SciFiRender.roundedRectWithBorder(g, getX(), y, getWidth(), h, SBSTheme.CORNER_RADIUS,
                opaque(SBSTheme.SEARCH_FILL), opaque(SBSTheme.ACCENT));
        String current = value.get();
        int rowY = y + 1;
        for (int i = scroll; i < list.size() && i < scroll + MAX_VISIBLE; i++) {
            String option = list.get(i);
            boolean rowHovered = mouseX >= getX() && mouseX < getX() + getWidth()
                    && mouseY >= rowY && mouseY < rowY + ROW_H;
            boolean selected = option.equals(current);
            if (rowHovered || selected) {
                SciFiRender.roundedRect(g, getX() + 1, rowY, getWidth() - 2, ROW_H - 1, 2,
                        opaque(rowHovered ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG));
            }
            g.text(font, Component.literal(RowText.fit(font, option, getWidth() - 10)), getX() + 5,
                    rowY + (ROW_H - font.lineHeight) / 2,
                    selected ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT);
            rowY += ROW_H;
        }
        if (list.size() > MAX_VISIBLE) {
            g.text(font, Component.literal("§8" + (scroll + 1) + "-"
                            + Math.min(list.size(), scroll + MAX_VISIBLE) + "/" + list.size()),
                    getX() + getWidth() - 34, y + h - font.lineHeight - 1, SBSTheme.TEXT_MUTED);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        double mx = event.x();
        double my = event.y();
        if (open) {
            List<String> list = options.get();
            int y = listY();
            if (mx >= getX() && mx < getX() + getWidth() && my >= y && my < y + listHeight()) {
                int index = scroll + (int) ((my - y - 1) / ROW_H);
                if (index >= 0 && index < list.size()) {
                    onPick.accept(list.get(index));
                }
                open = false;
                return true;
            }
            open = false;   // click anywhere else just closes it
            return isMouseOver(mx, my);
        }
        if (isMouseOver(mx, my)) {
            open = true;
            scroll = 0;
            if (onOpen != null) {
                onOpen.run();   // close sibling dropdowns so only one list is ever open
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!open || scrollY == 0) {
            return false;
        }
        int max = Math.max(0, options.get().size() - MAX_VISIBLE);
        scroll = Math.max(0, Math.min(scroll - (int) Math.signum(scrollY), max));
        return true;
    }

    /** The same colour at full alpha - the popup may not let anything behind it show through. */
    private static int opaque(int argb) {
        return argb | 0xFF000000;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE,
                Component.literal(label + ": " + value.get()));
    }
}
