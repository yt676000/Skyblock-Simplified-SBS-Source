/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.skills.farming.logic.MousematSign;

/**
 * The Squeaky Mousemat's angle sign: fills in the saved angle for the crop you were last farming,
 * and draws the crop panel beside it. All of the thinking lives in {@link MousematSign}; this only
 * connects it to the screen.
 *
 * <p>Separate from {@link SignSearchMixin} even though both hook the same screen: that one owns the
 * Bazaar/AH search history, and the two signs are told apart by their own contents, never by each
 * other. Neither can match the sign the other is for.
 *
 * <p><b>Why no key is taken.</b> Up and down are how you move between a sign's lines - taking them
 * for the crop list would mean you could no longer reach the pitch line to type on it, which costs
 * more than it gives. Picking a different crop is the exception, not the rule, so it is a click
 * (routed in through {@link SearchSignMouseMixin}, since the sign screen declares no mouse method).
 */
@Mixin(AbstractSignEditScreen.class)
public abstract class MousematSignMixin {

    @Shadow
    private String[] messages;

    @Shadow
    private int line;

    @Shadow
    private void setMessage(String message) {
    }

    /** Whether this sign is the Mousemat's - decided once, at open. */
    @Unique
    private boolean skyblockSimplified$angleSign;

    /**
     * Whether the saved angle has already been typed in. {@code init} runs again every time the
     * window is resized, and filling a second time would replace whatever the player had typed in
     * the meantime with the stored value.
     */
    @Unique
    private boolean skyblockSimplified$filled;

    @Inject(method = "init", at = @At("TAIL"))
    private void skyblockSimplified$openAngleSign(CallbackInfo ci) {
        skyblockSimplified$angleSign = MousematSign.getInstance().onSignOpened(
                messages, this::skyblockSimplified$writeLine, !skyblockSimplified$filled);
        if (skyblockSimplified$angleSign) {
            skyblockSimplified$filled = true;
        }
    }

    /**
     * Types one line of the sign. {@code setMessage} always writes the line the cursor is on, so the
     * cursor is moved, the line written, and then parked back on the first input - which is where a
     * player who wants to retype the value expects to find it.
     */
    @Unique
    private void skyblockSimplified$writeLine(int index, String text) {
        if (messages == null || index < 0 || index >= messages.length) {
            return;
        }
        int previous = this.line;
        this.line = index;
        setMessage(text);
        this.line = previous;
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void skyblockSimplified$renderCrops(GuiGraphicsExtractor g, int mouseX, int mouseY,
                                                 float partialTick, CallbackInfo ci) {
        if (skyblockSimplified$angleSign) {
            MousematSign.getInstance().render(g, Minecraft.getInstance().font, mouseX, mouseY);
        }
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void skyblockSimplified$closeAngleSign(CallbackInfo ci) {
        if (skyblockSimplified$angleSign) {
            skyblockSimplified$angleSign = false;
            MousematSign.getInstance().onSignClosed(messages);
        }
    }
}
