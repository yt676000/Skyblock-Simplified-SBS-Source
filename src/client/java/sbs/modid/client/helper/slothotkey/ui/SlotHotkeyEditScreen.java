/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.slothotkey.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.component.SciFiCycleButton;
import sbs.modid.client.ui.component.SciFiKeyCaptureButton;
import sbs.modid.client.ui.component.SciFiTextField;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.core.keybind.CommandKeybind;
import sbs.modid.client.helper.slothotkey.logic.SlotHotkey;
import sbs.modid.client.helper.slothotkey.logic.SlotHotkeyManager;

/**
 * Editor for one {@link SlotHotkey}: a name, the trigger key or mouse button, an optional modifier,
 * and a read-only reminder of which slot / menu it clicks. Reached from the {@link SlotHotkeysScreen}
 * list (existing hotkey) or its Add button (a fresh one made from the scanned slot). Every field
 * saves immediately, so leaving via Back needs no explicit confirm.
 */
public final class SlotHotkeyEditScreen extends Screen {

    /** Modifier presets in cycle order. */
    private static final int[] MOD_ORDER = {
            0, CommandKeybind.MOD_SHIFT, CommandKeybind.MOD_CTRL, CommandKeybind.MOD_ALT,
            CommandKeybind.MOD_SHIFT | CommandKeybind.MOD_CTRL,
            CommandKeybind.MOD_SHIFT | CommandKeybind.MOD_ALT,
            CommandKeybind.MOD_CTRL | CommandKeybind.MOD_ALT,
            CommandKeybind.MOD_SHIFT | CommandKeybind.MOD_CTRL | CommandKeybind.MOD_ALT};

    private final SlotHotkey hotkey;
    private final SlotHotkeyManager manager = SlotHotkeyManager.getInstance();

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int dividerY;
    private int innerX;
    private int contentWidth;
    private int rowsTop;
    private int backY;
    private int rowStep;
    private int infoY;

    public SlotHotkeyEditScreen(SlotHotkey hotkey) {
        super(Component.literal("Edit Slot Hotkey"));
        this.hotkey = hotkey;
    }

    @Override
    protected void init() {
        panelW = clamp(this.width - SBSTheme.SCREEN_MARGIN * 2, 300, 400);
        panelH = clamp(this.height - SBSTheme.SCREEN_MARGIN * 2, 200, 280);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        dividerY = panelY + SBSTheme.HEADER_HEIGHT;
        int pad = SBSTheme.PANEL_PADDING;
        innerX = panelX + pad;
        contentWidth = panelW - pad * 2;
        rowsTop = dividerY + SBSTheme.GAP_AFTER_HEADER;
        backY = panelY + panelH - pad - SBSTheme.SEARCH_HEIGHT;
        rowStep = SBSTheme.ENTRY_HEIGHT + SBSTheme.ENTRY_SPACING;

        addRenderableOnly(new PanelRenderable());

        int y = rowsTop;

        addRenderableWidget(SciFiTextField.forRow(innerX, y, contentWidth, SBSTheme.ENTRY_HEIGHT,
                "Name", "Optional name...", 48, hotkey::name,
                value -> { hotkey.setName(value); manager.save(); }));
        y += rowStep;

        int half = (contentWidth - 6) / 2;
        addRenderableWidget(new SciFiKeyCaptureButton(innerX, y, half, SBSTheme.ENTRY_HEIGHT,
                "Key", hotkey::keyCode,
                code -> { hotkey.setKeyCode(code); manager.save(); }));
        addRenderableWidget(new SciFiCycleButton(innerX + half + 6, y, contentWidth - half - 6,
                SBSTheme.ENTRY_HEIGHT, Component.literal("Modifier"),
                () -> Component.literal(modLabel(hotkey.modifiers())),
                () -> { hotkey.setModifiers(nextMod(hotkey.modifiers())); manager.save(); }));
        y += rowStep;

        infoY = y + 2;

        addRenderableWidget(new SciFiButton(innerX, backY, contentWidth, SBSTheme.SEARCH_HEIGHT,
                Component.literal("Back"), this::onBack));
    }

    private void onBack() {
        manager.save();
        Minecraft.getInstance().setScreenAndShow(new SlotHotkeysScreen());
    }

    private static String modLabel(int modifiers) {
        String prefix = SlotHotkey.modifierPrefix(modifiers);
        return prefix.isEmpty() ? "None" : prefix.substring(0, prefix.length() - 3);
    }

    private static int nextMod(int modifiers) {
        for (int i = 0; i < MOD_ORDER.length; i++) {
            if (MOD_ORDER[i] == modifiers) {
                return MOD_ORDER[(i + 1) % MOD_ORDER.length];
            }
        }
        return 0;
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
            var font = SlotHotkeyEditScreen.this.font;
            g.fill(0, 0, SlotHotkeyEditScreen.this.width, SlotHotkeyEditScreen.this.height, SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

            int titleY = panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2;
            g.centeredText(font, Component.literal("Edit Slot Hotkey"),
                    panelX + panelW / 2, titleY, SBSTheme.ACCENT_BRIGHT);
            int pad = SBSTheme.PANEL_PADDING;
            g.fill(panelX + pad, dividerY, panelX + panelW - pad, dividerY + 1, SBSTheme.ACCENT);

            String menu = hotkey.menuName().isEmpty() ? "this menu" : hotkey.menuName();
            g.text(font, Component.literal("§8Clicks §7slot " + hotkey.slot() + " §8in §7" + menu),
                    innerX, infoY, SBSTheme.TEXT_MUTED);
            g.text(font, Component.literal("§8Right-click the Key field to unbind"),
                    innerX, infoY + font.lineHeight + 2, SBSTheme.TEXT_MUTED);
        }
    }
}
