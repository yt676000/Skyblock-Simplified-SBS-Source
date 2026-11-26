/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.localflips.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.ui.theme.KeyedScreen;

/**
 * Hosts the {@link LocalFlipsWindow} when no container is open (the open key in-world, the
 * settings button). No background dim and no pause. Inside a container the window needs no host:
 * it is one of the floating windows there, beside the Auction House and the others.
 */
public final class LocalFlipsScreen extends Screen implements KeyedScreen {

    public LocalFlipsScreen() {
        super(Component.literal("Flip Finder"));
    }

    @Override
    public String screenId() {
        return "local_flips";
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // no dim: the world stays visible behind the window
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        LocalFlipsWindow window = LocalFlipsWindow.getInstance();
        if (!window.isOpen()) {
            Minecraft.getInstance().setScreenAndShow(null);
            return;
        }
        window.render(this, g, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        return LocalFlipsWindow.getInstance().handleClick(this, event) || super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        return LocalFlipsWindow.getInstance().handleDrag(this, event) || super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        return LocalFlipsWindow.getInstance().handleRelease(event) || super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return LocalFlipsWindow.getInstance().handleScroll(this, mouseX, mouseY, scrollY)
                || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        LocalFlipsWindow.getInstance().close();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
