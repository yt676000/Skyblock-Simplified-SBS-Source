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
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.core.module.ModuleCategory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A vertically scrollable, Sci-Fi styled list of module cards.
 *
 * <p>Implemented as a single {@link AbstractWidget} so it owns its own scissor
 * clipping, scroll offset, mouse-wheel handling and click hit-testing – the proper
 * way to do a scroll area in this version's "extract render state" system. Cards are
 * drawn directly (not as child widgets), so they are clipped cleanly to the list
 * bounds and stay comfortable to use with many modules (16–64+).
 */
public class SciFiModuleList extends AbstractWidget {

    private static final int SCROLL_STEP = 16;
    private static final int SCROLLBAR_WIDTH = 4;

    private final List<ModuleCategory> entries = new ArrayList<>();
    private final Consumer<ModuleCategory> onSelect;

    private double scroll;
    private int lastMouseX;
    private int lastMouseY;

    public SciFiModuleList(int x, int y, int width, int height, Consumer<ModuleCategory> onSelect) {
        super(x, y, width, height, Component.literal("Modules"));
        this.onSelect = onSelect;
    }

    public void setEntries(List<ModuleCategory> list) {
        entries.clear();
        entries.addAll(list);
        scroll = 0;
        clampScroll();
    }

    private int stride() {
        return SBSTheme.ENTRY_HEIGHT + SBSTheme.ENTRY_SPACING;
    }

    private int contentHeight() {
        return entries.isEmpty() ? 0 : entries.size() * stride() - SBSTheme.ENTRY_SPACING;
    }

    private int maxScroll() {
        return Math.max(0, contentHeight() - getHeight());
    }

    private boolean hasScrollbar() {
        return maxScroll() > 0;
    }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
    }

    private int listWidth() {
        return getWidth() - (hasScrollbar() ? SCROLLBAR_WIDTH + 2 : 0);
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        var font = Minecraft.getInstance().font;
        int x = getX();
        int y = getY();
        int w = getWidth();
        int h = getHeight();
        int listW = listWidth();

        g.enableScissor(x, y, x + w, y + h);
        if (entries.isEmpty()) {
            g.centeredText(font, Component.literal("No modules found"),
                    x + w / 2, y + h / 2 - font.lineHeight / 2, SBSTheme.TEXT_MUTED);
        } else {
            int cy = y - (int) Math.round(scroll);
            for (ModuleCategory cat : entries) {
                int bottom = cy + SBSTheme.ENTRY_HEIGHT;
                if (bottom >= y && cy <= y + h) {
                    boolean hovered = mouseX >= x && mouseX <= x + listW
                            && mouseY >= Math.max(y, cy) && mouseY <= Math.min(y + h, bottom);
                    int bg = hovered ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG;
                    int border = hovered ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER;
                    SciFiRender.roundedRectWithBorder(g, x, cy, listW, SBSTheme.ENTRY_HEIGHT,
                            SBSTheme.CORNER_RADIUS, bg, border);
                    if (hovered) {
                        g.fill(x + 2, cy + 3, x + 4, cy + SBSTheme.ENTRY_HEIGHT - 3, SBSTheme.ACCENT);
                    }
                    g.text(font, cat.displayName(), x + 10,
                            cy + (SBSTheme.ENTRY_HEIGHT - font.lineHeight) / 2, SBSTheme.TEXT);
                }
                cy += stride();
            }
        }
        g.disableScissor();

        if (hasScrollbar()) {
            drawScrollbar(g, x, y, w, h);
        }
    }

    private void drawScrollbar(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        int sbX = x + w - SCROLLBAR_WIDTH;
        SciFiRender.roundedRect(g, sbX, y, SCROLLBAR_WIDTH, h, SCROLLBAR_WIDTH / 2, 0x22FFFFFF);
        int content = contentHeight();
        int thumbH = Math.max(16, (int) ((long) h * h / content));
        int travel = h - thumbH;
        int max = maxScroll();
        int thumbY = y + (max <= 0 ? 0 : (int) (scroll / max * travel));
        SciFiRender.roundedRect(g, sbX, thumbY, SCROLLBAR_WIDTH, thumbH, SCROLLBAR_WIDTH / 2, SBSTheme.ACCENT);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!hasScrollbar()) {
            return false;
        }
        scroll -= scrollY * SCROLL_STEP;
        clampScroll();
        return true;
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        if (entries.isEmpty() || lastMouseX > getX() + listWidth()) {
            return;
        }
        int rel = lastMouseY - getY() + (int) Math.round(scroll);
        if (rel < 0) {
            return;
        }
        int index = rel / stride();
        int within = rel - index * stride();
        if (index >= 0 && index < entries.size() && within <= SBSTheme.ENTRY_HEIGHT && onSelect != null) {
            onSelect.accept(entries.get(index));
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        // The list container has no specific narration.
    }
}
