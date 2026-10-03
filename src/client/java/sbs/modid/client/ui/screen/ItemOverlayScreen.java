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
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.component.SciFiCycleButton;
import sbs.modid.client.ui.component.SciFiRangeSlider;
import sbs.modid.client.ui.component.SciFiToggleButton;
import sbs.modid.client.ui.theme.SBSTheme;

/**
 * The Item Overlay module overlay (Render category), opened from the Item Overlay card.
 *
 * <p>Uses the exact SBS layout / header / back-button design shared by the other module screens
 * (panel painted by {@link PanelRenderable}, spacing and colors from {@link SBSTheme}, reusing
 * {@link SciFiToggleButton} and {@link SciFiButton}). Two toggles: "Show Item Rarity" and "Show LBIN".
 */
public final class ItemOverlayScreen extends Screen {

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

    public ItemOverlayScreen() {
        super(Component.literal("Item Overlay"));
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

        addRenderableWidget(new SciFiCycleButton(innerX, rowY, contentWidth, controlHeight,
                Component.literal("Show Item Rarity"),
                () -> Component.literal(settings().rarityMode.displayName()),
                this::cycleRarity));
        rowY += rowStep;

        addRenderableWidget(new SciFiRangeSlider(innerX, rowY, contentWidth, controlHeight,
                Component.literal("Rarity Overlay Opacity"),
                5, 80, settings().rarityOpacity, "%",
                this::setRarityOpacity));
        rowY += rowStep;

        addRenderableWidget(new SciFiToggleButton(innerX, rowY, contentWidth, controlHeight,
                Component.literal("Show LBIN"),
                () -> settings().showLbin,
                this::toggleLbin));
        rowY += rowStep;

        addRenderableWidget(new SciFiToggleButton(innerX, rowY, contentWidth, controlHeight,
                Component.literal("Show Lowest Bazaar Price"),
                () -> settings().showBazaarPrice,
                this::toggleBazaarPrice));
        rowY += rowStep;

        addRenderableWidget(new SciFiToggleButton(innerX, rowY, contentWidth, controlHeight,
                Component.literal("Show Item Cooldown"),
                () -> settings().showItemCooldown,
                this::toggleItemCooldown));
        rowY += rowStep;

        addRenderableWidget(new SciFiToggleButton(innerX, rowY, contentWidth, controlHeight,
                Component.literal("Enchanted Book Abbreviation"),
                () -> settings().bookAbbreviation,
                this::toggleBookAbbreviation));
        rowY += rowStep;

        addRenderableWidget(new SciFiToggleButton(innerX, rowY, contentWidth, controlHeight,
                Component.literal("Enchanted Book Tier"),
                () -> settings().bookTier,
                this::toggleBookTier));
        rowY += rowStep;

        addRenderableWidget(new SciFiToggleButton(innerX, rowY, contentWidth, controlHeight,
                Component.literal("Item Renamer"),
                () -> settings().itemRenamer,
                this::toggleItemRenamer));
        rowY += rowStep;

        // Small hint lines so the commands are discoverable right at the setting.
        addRenderableWidget(new SciFiButton(innerX, rowY, contentWidth, this.font.lineHeight + 2,
                Component.literal("/sbs itemrename <new name>  •  /sbs itemoriginalname"), null).asLabel());

        addRenderableWidget(new SciFiButton(innerX, backY, contentWidth, SBSTheme.SEARCH_HEIGHT,
                Component.literal("Back"), this::onBack));
    }

    private static SBSConfig.ItemOverlaySettings settings() {
        return ConfigManager.getInstance().get().itemOverlay;
    }

    private void cycleRarity() {
        settings().rarityMode = settings().rarityMode.next();
        ConfigManager.getInstance().save();
    }

    private void setRarityOpacity(int percent) {
        settings().rarityOpacity = percent;
        ConfigManager.getInstance().save();
    }

    private void toggleItemCooldown() {
        settings().showItemCooldown = !settings().showItemCooldown;
        ConfigManager.getInstance().save();
    }

    private void toggleItemRenamer() {
        settings().itemRenamer = !settings().itemRenamer;
        ConfigManager.getInstance().save();
    }

    private void toggleBookAbbreviation() {
        settings().bookAbbreviation = !settings().bookAbbreviation;
        ConfigManager.getInstance().save();
    }

    private void toggleBookTier() {
        settings().bookTier = !settings().bookTier;
        ConfigManager.getInstance().save();
    }

    private void toggleLbin() {
        settings().showLbin = !settings().showLbin;
        ConfigManager.getInstance().save();
        if (settings().showLbin) {
            // Fetch right away instead of waiting for the next scheduled cycle (up to 5 minutes).
            sbs.modid.client.economy.prices.LbinCache.getInstance().requestRefresh();
        }
    }

    private void toggleBazaarPrice() {
        settings().showBazaarPrice = !settings().showBazaarPrice;
        ConfigManager.getInstance().save();
        if (settings().showBazaarPrice) {
            sbs.modid.client.economy.prices.BazaarPriceCache.getInstance().requestRefresh();
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
            var font = ItemOverlayScreen.this.font;

            g.fill(0, 0, ItemOverlayScreen.this.width, ItemOverlayScreen.this.height, SBSTheme.BG_TINT);

            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

            int titleY = panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2;
            g.centeredText(font, Component.literal("Item Overlay"), panelX + panelW / 2, titleY, SBSTheme.ACCENT_BRIGHT);
            int pad = SBSTheme.PANEL_PADDING;
            g.fill(panelX + pad, dividerY, panelX + panelW - pad, dividerY + 1, SBSTheme.ACCENT);
            g.fill(panelX + pad, dividerY, panelX + pad + 10, dividerY + 1, SBSTheme.ACCENT_BRIGHT);
            g.fill(panelX + panelW - pad - 10, dividerY, panelX + panelW - pad, dividerY + 1, SBSTheme.ACCENT_BRIGHT);

            g.text(font, Component.literal("Settings"), innerX, settingsLabelY, SBSTheme.TEXT_MUTED);
        }
    }
}
