/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.recipe.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.component.SciFiToggleButton;
import sbs.modid.client.ui.screen.SBSMainScreen;
import sbs.modid.client.ui.theme.SBSTheme;

/**
 * The Recipe Viewer module overlay: the Enabled toggle, an "Open Recipe Viewer" action (pinned on
 * top, mirroring the GUI module's Edit GUI pattern) and the standard Back button. Shares the exact
 * SBS layout / header / divider design of every other module screen.
 */
public final class RecipeViewerModuleScreen extends Screen {

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int dividerY;
    private int innerX;
    private int contentWidth;
    private int settingsLabelY;
    private int backY;
    private int controlHeight;

    public RecipeViewerModuleScreen() {
        super(Component.literal("Recipe Viewer"));
    }

    @Override
    protected void init() {
        panelW = clamp(this.width - SBSTheme.SCREEN_MARGIN * 2, SBSTheme.PANEL_MIN_WIDTH, SBSTheme.PANEL_MAX_WIDTH);
        panelH = clamp(this.height - SBSTheme.SCREEN_MARGIN * 2, SBSTheme.PANEL_MIN_HEIGHT, SBSTheme.PANEL_MAX_HEIGHT);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        dividerY = panelY + SBSTheme.HEADER_HEIGHT;
        innerX = panelX + SBSTheme.PANEL_PADDING;
        contentWidth = panelW - SBSTheme.PANEL_PADDING * 2;

        controlHeight = SBSTheme.ENTRY_HEIGHT;
        settingsLabelY = dividerY + SBSTheme.GAP_AFTER_HEADER;
        backY = panelY + panelH - SBSTheme.PANEL_PADDING - SBSTheme.SEARCH_HEIGHT;

        addRenderableOnly(new PanelRenderable());

        int rowStep = controlHeight + SBSTheme.ENTRY_SPACING;
        int rowY = settingsLabelY + this.font.lineHeight + SBSTheme.GAP_AFTER_SEARCH;

        addRenderableWidget(new SciFiButton(innerX, rowY, contentWidth, controlHeight,
                Component.literal("Open Recipe Viewer"), this::openViewer));
        rowY += rowStep;

        addRenderableWidget(new SciFiToggleButton(innerX, rowY, contentWidth, controlHeight,
                Component.literal("Enabled"),
                () -> settings().enabled,
                this::toggleEnabled));

        addRenderableWidget(new SciFiButton(innerX, backY, contentWidth, SBSTheme.SEARCH_HEIGHT,
                Component.literal("Back"), this::onBack));
    }

    private static SBSConfig.RecipeViewerSettings settings() {
        return ConfigManager.getInstance().get().recipeViewer;
    }

    private void toggleEnabled() {
        settings().enabled = !settings().enabled;
        ConfigManager.getInstance().save();
    }

    private void openViewer() {
        if (settings().enabled) {
            Minecraft.getInstance().setScreenAndShow(new RecipeViewerScreen());
        }
    }

    private void onBack() {
        Minecraft.getInstance().setScreenAndShow(new SBSMainScreen());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private final class PanelRenderable implements Renderable {

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            var font = RecipeViewerModuleScreen.this.font;

            g.fill(0, 0, RecipeViewerModuleScreen.this.width, RecipeViewerModuleScreen.this.height, SBSTheme.BG_TINT);

            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

            int titleY = panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2;
            g.centeredText(font, Component.literal("Recipe Viewer"), panelX + panelW / 2, titleY, SBSTheme.ACCENT_BRIGHT);
            int pad = SBSTheme.PANEL_PADDING;
            g.fill(panelX + pad, dividerY, panelX + panelW - pad, dividerY + 1, SBSTheme.ACCENT);
            g.fill(panelX + pad, dividerY, panelX + pad + 10, dividerY + 1, SBSTheme.ACCENT_BRIGHT);
            g.fill(panelX + panelW - pad - 10, dividerY, panelX + panelW - pad, dividerY + 1, SBSTheme.ACCENT_BRIGHT);

            g.text(font, Component.literal("Settings"), innerX, settingsLabelY, SBSTheme.TEXT_MUTED);
        }
    }
}
