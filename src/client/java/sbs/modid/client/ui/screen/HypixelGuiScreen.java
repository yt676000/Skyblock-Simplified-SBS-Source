/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.ui.hud.edit.ui.HudEditorScreen;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.component.SciFiCycleButton;
import sbs.modid.client.ui.component.SciFiToggleButton;
import sbs.modid.client.ui.theme.SBSTheme;

/**
 * The GUI module overlay, opened from the GUI card in the main menu.
 *
 * <p>Shares the exact sci-fi look of the other SBS screens (panel / header / divider painted by
 * {@link PanelRenderable}, colors and spacing from {@link SBSTheme}, reusing {@link SciFiCycleButton},
 * {@link SciFiToggleButton} and {@link SciFiButton}). Layout: the title in the header, the HUD
 * settings (Health Bar mode, Mana Bar mode, Hide Armor Bar, Hide Potion Effect Status) and an
 * "Edit GUI" button stacked in the centre, and a Back button at the bottom.
 */
public final class HypixelGuiScreen extends Screen {

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

    public HypixelGuiScreen() {
        super(Component.literal("GUI"));
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

        // "Edit GUI" is the module's most important action, so it is pinned to the very top of the
        // list – intentionally ignoring alphabetical ordering, but only inside this module.
        addRenderableWidget(new SciFiButton(innerX, rowY, contentWidth, controlHeight,
                Component.literal("Edit GUI"), this::onEditGui));
        rowY += rowStep;

        addRenderableWidget(new SciFiCycleButton(innerX, rowY, contentWidth, controlHeight,
                Component.literal("Health Bar"),
                () -> Component.literal(settings().healthBar.displayName()),
                this::cycleHealthBar));
        rowY += rowStep;

        addRenderableWidget(new SciFiCycleButton(innerX, rowY, contentWidth, controlHeight,
                Component.literal("Mana Bar"),
                () -> Component.literal(settings().manaBar.displayName()),
                this::cycleManaBar));
        rowY += rowStep;

        addRenderableWidget(new SciFiToggleButton(innerX, rowY, contentWidth, controlHeight,
                Component.literal("Hide Armor Bar"),
                () -> settings().hideArmorBar,
                this::toggleHideArmorBar));
        rowY += rowStep;

        addRenderableWidget(new SciFiToggleButton(innerX, rowY, contentWidth, controlHeight,
                Component.literal("Hide Potion Effect Status"),
                () -> settings().hidePotionEffects,
                this::toggleHidePotionEffects));
        rowY += rowStep;

        addRenderableWidget(new SciFiCycleButton(innerX, rowY, contentWidth, controlHeight,
                Component.literal("Recipe Viewer Position"),
                () -> Component.literal(settings().recipeViewerPosition.displayName()),
                this::cycleRecipeViewerPosition));
        rowY += rowStep;

        addRenderableWidget(new SciFiToggleButton(innerX, rowY, contentWidth, controlHeight,
                Component.literal("Show Active Pet"),
                () -> settings().showActivePet,
                this::toggleShowActivePet));
        rowY += rowStep;

        addRenderableWidget(new SciFiToggleButton(innerX, rowY, contentWidth, controlHeight,
                Component.literal("Show Item Cooldown (HUD)"),
                () -> settings().showCooldownHud,
                this::toggleShowCooldownHud));

        addRenderableWidget(new SciFiButton(innerX, backY, contentWidth, SBSTheme.SEARCH_HEIGHT,
                Component.literal("Back"), this::onBack));
    }

    private static SBSConfig.HypixelGuiSettings settings() {
        return ConfigManager.getInstance().get().hypixelGui;
    }

    private void cycleHealthBar() {
        settings().healthBar = settings().healthBar.next();
        ConfigManager.getInstance().save();
    }

    private void cycleManaBar() {
        settings().manaBar = settings().manaBar.next();
        ConfigManager.getInstance().save();
    }

    private void toggleHideArmorBar() {
        settings().hideArmorBar = !settings().hideArmorBar;
        ConfigManager.getInstance().save();
    }

    private void toggleHidePotionEffects() {
        settings().hidePotionEffects = !settings().hidePotionEffects;
        ConfigManager.getInstance().save();
    }

    private void cycleRecipeViewerPosition() {
        settings().recipeViewerPosition = settings().recipeViewerPosition.next();
        ConfigManager.getInstance().save();
    }

    private void toggleShowActivePet() {
        settings().showActivePet = !settings().showActivePet;
        ConfigManager.getInstance().save();
    }

    private void toggleShowCooldownHud() {
        settings().showCooldownHud = !settings().showCooldownHud;
        ConfigManager.getInstance().save();
    }

    private void onEditGui() {
        Minecraft.getInstance().setScreenAndShow(new HudEditorScreen());
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
            var font = HypixelGuiScreen.this.font;

            g.fill(0, 0, HypixelGuiScreen.this.width, HypixelGuiScreen.this.height, SBSTheme.BG_TINT);

            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

            int titleY = panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2;
            g.centeredText(font, Component.literal("GUI"), panelX + panelW / 2, titleY, SBSTheme.ACCENT_BRIGHT);
            int pad = SBSTheme.PANEL_PADDING;
            g.fill(panelX + pad, dividerY, panelX + panelW - pad, dividerY + 1, SBSTheme.ACCENT);
            g.fill(panelX + pad, dividerY, panelX + pad + 10, dividerY + 1, SBSTheme.ACCENT_BRIGHT);
            g.fill(panelX + panelW - pad - 10, dividerY, panelX + panelW - pad, dividerY + 1, SBSTheme.ACCENT_BRIGHT);

            g.text(font, Component.literal("Settings"), innerX, settingsLabelY, SBSTheme.TEXT_MUTED);
        }
    }
}
