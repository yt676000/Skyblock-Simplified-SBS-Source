/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.core.api.ScreenAccess;
import sbs.modid.client.economy.bazaar.logic.BazaarSearchHistory;

/**
 * Makes the panels SBS draws over Hypixel's signs clickable – the Bazaar / Auction House search
 * history, and the Squeaky Mousemat's crop list.
 *
 * <p>Those panels are drawn over a sign edit screen, and that screen has no mouse method to inject
 * into: neither {@code AbstractSignEditScreen} nor {@code Screen} declares {@code mouseClicked} – it
 * arrives as a default method on {@code ContainerEventHandler}, which is not a class a mixin can
 * usefully take here. So the click is caught one level down instead, at the mouse handler's own
 * button callback, which every click passes through before any screen sees it.
 *
 * <p>Three conditions keep that from being invasive: only a left press is looked at, only while the
 * open screen is a sign edit screen, and only when a row of a panel is actually under the cursor.
 * Anything else falls straight through to vanilla, so this is inert everywhere but on the one screen
 * it exists for. Only one panel is ever up at a time – each recognises its own sign, and no sign is
 * both – so the two are offered the click in turn and at most one takes it.
 */
@Mixin(MouseHandler.class)
public abstract class SearchSignMouseMixin {

    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$searchPanelClick(long window, MouseButtonInfo info, int action,
                                                     CallbackInfo ci) {
        if (action != 1 || info == null || info.button() != 0) {
            return;   // not a fresh left press
        }
        if (!(ScreenAccess.current() instanceof AbstractSignEditScreen)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Window win = minecraft.getWindow();
        MouseHandler self = (MouseHandler) (Object) this;
        // The panel is laid out in GUI-scaled coordinates, so the raw cursor has to be scaled the
        // same way vanilla scales it before handing a click to a screen.
        double x = MouseHandler.getScaledXPos(win, self.xpos());
        double y = MouseHandler.getScaledYPos(win, self.ypos());
        if (BazaarSearchHistory.getInstance().click(x, y)
                || sbs.modid.client.skills.farming.logic.MousematSign.getInstance().click(x, y)) {
            ci.cancel();
        }
    }
}
