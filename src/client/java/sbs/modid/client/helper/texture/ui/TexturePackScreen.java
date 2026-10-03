/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.texture.ui;

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
import sbs.modid.client.ui.component.SciFiToggleButton;
import sbs.modid.client.ui.screen.SBSMainScreen;
import sbs.modid.client.ui.theme.SBSTheme;

/**
 * The Texture Pack module overlay (Visual category): a single state cycle ("Pack Theme Mode") whose
 * value is persisted and drives the {@link sbs.modid.client.helper.texture.model.TexturePackState} routing hook.
 * Uses the standard SBS layout / header / back-button design.
 */
public final class TexturePackScreen extends Screen {

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

    public TexturePackScreen() {
        super(Component.literal("Texture Pack"));
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
                Component.literal("Pack Theme Mode"),
                () -> Component.literal(settings().packTheme.displayName()),
                this::cyclePack));
        rowY += rowStep;

        addRenderableWidget(new SciFiToggleButton(innerX, rowY, contentWidth, controlHeight,
                Component.literal("Ignore Enforced Texture Packs"),
                () -> settings().ignoreEnforcedPacks,
                this::toggleIgnoreEnforcedPacks));
        rowY += rowStep;

        addRenderableWidget(new SciFiToggleButton(innerX, rowY, contentWidth, controlHeight,
                Component.literal("Auto-Accept Server Packs"),
                () -> settings().autoAcceptServerPacks,
                this::toggleAutoAcceptServerPacks));
        rowY += rowStep;

        addRenderableWidget(new SciFiButton(innerX, rowY, contentWidth, controlHeight,
                Component.literal("Hypixel+: Open download page"), this::openHypixelPlusPage));
        rowY += rowStep;

        addRenderableWidget(new SciFiButton(innerX, rowY, contentWidth, controlHeight,
                Component.literal("Furfsky Reborn: Open download page"), this::openFurfskyRebornPage));

        addRenderableWidget(new SciFiButton(innerX, backY, contentWidth, SBSTheme.SEARCH_HEIGHT,
                Component.literal("Back"), this::onBack));
    }

    private static SBSConfig.TexturePackSettings settings() {
        return ConfigManager.getInstance().get().texturePack;
    }

    /** Shared cycle logic (also used by the module settings row) - see {@link TexturePackActions}. */
    private void cyclePack() {
        sbs.modid.client.helper.texture.command.TexturePackActions.cycleMode();
    }

    /** Opens the Hypixel+ page; the player installs the pack themselves. */
    private void openHypixelPlusPage() {
        sbs.modid.client.helper.texture.command.TexturePackActions.openDownloadPage(
                sbs.modid.client.helper.texture.logic.UserPack.HYPIXEL_PLUS);
    }

    /** Opens the Furfsky Reborn page; the player installs the pack themselves. */
    private void openFurfskyRebornPage() {
        sbs.modid.client.helper.texture.command.TexturePackActions.openDownloadPage(
                sbs.modid.client.helper.texture.logic.UserPack.FURFSKY_REBORN);
    }

    private void toggleIgnoreEnforcedPacks() {
        settings().ignoreEnforcedPacks = !settings().ignoreEnforcedPacks;
        ConfigManager.getInstance().save();
        // The fallback pack is Minecraft's own cached copy; it comes and goes with the next reload.
    }

    /** Accepts the server's required-pack prompt for you (ignored while the bypass above is on). */
    private void toggleAutoAcceptServerPacks() {
        settings().autoAcceptServerPacks = !settings().autoAcceptServerPacks;
        ConfigManager.getInstance().save();
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
            var font = TexturePackScreen.this.font;

            g.fill(0, 0, TexturePackScreen.this.width, TexturePackScreen.this.height, SBSTheme.BG_TINT);

            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

            int titleY = panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2;
            g.centeredText(font, Component.literal("Texture Pack"), panelX + panelW / 2, titleY, SBSTheme.ACCENT_BRIGHT);
            int pad = SBSTheme.PANEL_PADDING;
            g.fill(panelX + pad, dividerY, panelX + panelW - pad, dividerY + 1, SBSTheme.ACCENT);
            g.fill(panelX + pad, dividerY, panelX + pad + 10, dividerY + 1, SBSTheme.ACCENT_BRIGHT);
            g.fill(panelX + panelW - pad - 10, dividerY, panelX + panelW - pad, dividerY + 1, SBSTheme.ACCENT_BRIGHT);

            g.text(font, Component.literal("Settings"), innerX, settingsLabelY, SBSTheme.TEXT_MUTED);
        }
    }
}
