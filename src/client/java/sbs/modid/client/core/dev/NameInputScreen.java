/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.dev;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.function.Consumer;

/**
 * A minimal in-game name-input dialog for the dev tools. Extends {@link Screen} and reuses the SBS
 * theme so it matches the rest of the mod. Opened when a developer scans a room or drops a waypoint;
 * on confirm it hands the typed name back through a callback.
 *
 * <p>The two-field variant (used by Scan Room) adds a second input – the expected room size, for the
 * scan verification. The secret recorder is a separate dev keybind (it auto-binds to the room you are
 * standing in), so it is intentionally NOT a button here.
 *
 * <p>Rendering follows this project's pipeline: a {@link Renderable} added with
 * {@code addRenderableOnly} paints the panel via {@link GuiGraphicsExtractor}, and the widgets (edit
 * box + buttons) are added with {@code addRenderableWidget}. Enter confirms, Escape cancels.
 */
public final class NameInputScreen extends Screen {

    private static final int GLFW_KEY_ENTER = 257;
    private static final int GLFW_KEY_KP_ENTER = 335;

    private final Component prompt;
    private final String hint;
    private final Consumer<String> onSubmit;
    private final String secondHint;                               // null = single-field dialog
    private final java.util.function.BiConsumer<String, String> onSubmitTwo;

    private EditBox nameBox;
    private EditBox secondBox;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int innerX;
    private int contentWidth;
    private int fieldY;
    private int secondFieldY;

    public NameInputScreen(Component prompt, String hint, Consumer<String> onSubmit) {
        this(prompt, hint, onSubmit, null, null);
    }

    /**
     * Two-field variant with the secret-scan toggle: {@code onSubmitTwo} receives the name and the
     * (possibly empty) second field's text.
     */
    public NameInputScreen(Component prompt, String hint, String secondHint,
                           java.util.function.BiConsumer<String, String> onSubmitTwo) {
        this(prompt, hint, null, secondHint, onSubmitTwo);
    }

    private NameInputScreen(Component prompt, String hint, Consumer<String> onSubmit,
                            String secondHint, java.util.function.BiConsumer<String, String> onSubmitTwo) {
        super(prompt);
        this.prompt = prompt;
        this.hint = hint == null ? "" : hint;
        this.onSubmit = onSubmit;
        this.secondHint = secondHint;
        this.onSubmitTwo = onSubmitTwo;
    }

    @Override
    protected void init() {
        boolean twoFields = secondHint != null;
        panelW = Math.min(260, this.width - 40);
        panelH = SBSTheme.HEADER_HEIGHT + 90
                + (twoFields ? SBSTheme.ENTRY_HEIGHT + 8 : 0);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        innerX = panelX + SBSTheme.PANEL_PADDING;
        contentWidth = panelW - SBSTheme.PANEL_PADDING * 2;

        addRenderableOnly(new PanelRenderable());

        int textH = this.font.lineHeight;
        fieldY = panelY + SBSTheme.HEADER_HEIGHT + 20;
        int editY = fieldY + (SBSTheme.ENTRY_HEIGHT - textH) / 2;
        nameBox = new EditBox(this.font, innerX + 5, editY, contentWidth - 10, textH, Component.literal("Name"));
        nameBox.setBordered(false);
        nameBox.setMaxLength(64);
        nameBox.setTextColor(SBSTheme.TEXT);
        nameBox.setHint(Component.literal(hint));
        addRenderableWidget(nameBox);
        setInitialFocus(nameBox);
        nameBox.setFocused(true);

        int lastFieldBottom = fieldY + SBSTheme.ENTRY_HEIGHT;
        if (twoFields) {
            secondFieldY = lastFieldBottom + 8;
            int secondEditY = secondFieldY + (SBSTheme.ENTRY_HEIGHT - textH) / 2;
            secondBox = new EditBox(this.font, innerX + 5, secondEditY, contentWidth - 10, textH,
                    Component.literal("Size"));
            secondBox.setBordered(false);
            secondBox.setMaxLength(32);
            secondBox.setTextColor(SBSTheme.TEXT);
            secondBox.setHint(Component.literal(secondHint));
            addRenderableWidget(secondBox);
            lastFieldBottom = secondFieldY + SBSTheme.ENTRY_HEIGHT;
        }

        int gap = 6;
        int buttonW = (contentWidth - gap) / 2;
        int buttonsY = lastFieldBottom + 12;
        addRenderableWidget(new SciFiButton(innerX, buttonsY, buttonW, SBSTheme.SEARCH_HEIGHT,
                Component.literal("Confirm"), this::submit));
        addRenderableWidget(new SciFiButton(innerX + buttonW + gap, buttonsY, buttonW, SBSTheme.SEARCH_HEIGHT,
                Component.literal("Cancel"), this::onClose));
    }

    private void submit() {
        String name = nameBox.getValue().trim();
        if (name.isEmpty()) {
            return; // require a non-empty name; keep the dialog open
        }
        String second = secondBox != null ? secondBox.getValue().trim() : null;
        // Close first, then run the callback (the callback may scan the world / open nothing).
        Minecraft.getInstance().setScreenAndShow(null);
        if (onSubmitTwo != null) {
            onSubmitTwo.accept(name, second);
        } else if (onSubmit != null) {
            onSubmit.accept(name);
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (key == GLFW_KEY_ENTER || key == GLFW_KEY_KP_ENTER) {
            submit();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(null);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private final class PanelRenderable implements Renderable {

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            var font = NameInputScreen.this.font;

            g.fill(0, 0, NameInputScreen.this.width, NameInputScreen.this.height, SBSTheme.BG_TINT);

            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

            int titleY = panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2;
            g.centeredText(font, prompt, panelX + panelW / 2, titleY, SBSTheme.ACCENT_BRIGHT);

            // Edit-box backgrounds.
            SciFiRender.roundedRectWithBorder(g, innerX, fieldY, contentWidth, SBSTheme.ENTRY_HEIGHT,
                    SBSTheme.CORNER_RADIUS, SBSTheme.SEARCH_FILL,
                    nameBox != null && nameBox.isFocused() ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
            if (secondBox != null) {
                SciFiRender.roundedRectWithBorder(g, innerX, secondFieldY, contentWidth, SBSTheme.ENTRY_HEIGHT,
                        SBSTheme.CORNER_RADIUS, SBSTheme.SEARCH_FILL,
                        secondBox.isFocused() ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
            }
        }
    }
}
