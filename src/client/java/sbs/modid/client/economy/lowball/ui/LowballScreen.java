/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.lowball.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.ui.theme.KeyedScreen;

/**
 * Hosts the {@link LowballWindow} when no container is open ({@code /sbs lowball} on the held item,
 * {@code /sbs lowball last}). No background dim and no pause: the window is the whole screen. Esc
 * or the window's ✕ closes both.
 */
public final class LowballScreen extends Screen implements KeyedScreen {

    public LowballScreen() {
        super(Component.literal("Lowball"));
    }

    @Override
    public String screenId() {
        return "lowball";
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // no dim: the world stays visible behind the window
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        LowballWindow window = LowballWindow.getInstance();
        if (!window.isOpen()) {
            Minecraft.getInstance().setScreenAndShow(null);
            return;
        }
        window.render(this, g, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        return LowballWindow.getInstance().handleClick(this, event) || super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        return LowballWindow.getInstance().handleDrag(this, event) || super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        return LowballWindow.getInstance().handleRelease(event) || super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return LowballWindow.getInstance().handleScroll(this, mouseX, mouseY, scrollY)
                || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        LowballWindow.getInstance().close();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
