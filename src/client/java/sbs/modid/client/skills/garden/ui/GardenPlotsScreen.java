/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.garden.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.skills.garden.model.GardenPlotCatalog;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

/**
 * The Garden plot grid as a standalone screen, for the in-world hotkey.
 *
 * <p>Only the panel chrome lives here - the grid, the command buttons and the status line are drawn
 * by {@link GardenPlotsGrid}, the same code the floating inventory window uses. Two implementations
 * of one grid would drift apart the first time either of them changed.
 *
 * <p><b>Why a screen and not a HUD element.</b> The plots have to be clickable, and while you are
 * playing the mouse belongs to the camera - a HUD element can be drawn but never hit. Inside a
 * container the window form is the better one, which is what {@link GardenPlotsOverlay} is for.
 */
public final class GardenPlotsScreen extends Screen {

    private static final int CELL = 26;
    private static final int CELL_GAP = 3;
    /** Room for the button row and the status line the grid draws along the bottom. */
    private static final int FOOTER_H = 34;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int dividerY;
    private int contentX;
    private int contentY;
    private int contentW;
    private int contentH;

    public GardenPlotsScreen() {
        super(Component.literal("Garden Plots"));
    }

    @Override
    protected void init() {
        int size = GardenPlotCatalog.GRID_SIZE;
        int gridW = size * CELL + (size - 1) * CELL_GAP;
        int gridH = size * CELL + (size - 1) * CELL_GAP;

        int pad = SBSTheme.PANEL_PADDING;
        panelW = Math.max(gridW + pad * 2, 260);
        panelH = SBSTheme.HEADER_HEIGHT + SBSTheme.GAP_AFTER_HEADER + gridH + FOOTER_H + pad;
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        dividerY = panelY + SBSTheme.HEADER_HEIGHT;

        contentX = panelX + pad;
        contentY = dividerY + SBSTheme.GAP_AFTER_HEADER;
        contentW = panelW - pad * 2;
        contentH = panelY + panelH - pad - contentY;

        addRenderableOnly(new PanelRenderable());
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        return GardenPlotsGrid.click(contentX, contentY, contentW, contentH, event.x(), event.y());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private final class PanelRenderable implements Renderable {

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            g.fill(0, 0, GardenPlotsScreen.this.width, GardenPlotsScreen.this.height, SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER,
                    SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER,
                    SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

            String title = "Garden Plots";
            int titleY = panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2;
            g.text(font, Component.literal(title), panelX + (panelW - font.width(title)) / 2, titleY,
                    SBSTheme.ACCENT_BRIGHT);
            g.fill(panelX + SBSTheme.PANEL_PADDING, dividerY,
                    panelX + panelW - SBSTheme.PANEL_PADDING, dividerY + 1, SBSTheme.ACCENT);

            GardenPlotsGrid.draw(g, font, contentX, contentY, contentW, contentH, mouseX, mouseY, true);
        }
    }
}
