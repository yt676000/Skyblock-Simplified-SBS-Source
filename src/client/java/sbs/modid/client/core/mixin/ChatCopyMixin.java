/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.social.chat.logic.ChatCopy;
import sbs.modid.client.core.config.ConfigManager;

/**
 * Chat Options: CTRL + left-click a chat message to copy its clean text to the system clipboard.
 *
 * <p>Injects at the head of {@link ChatScreen#mouseClicked} and only acts when the setting is on, the
 * left button is used and CTRL is held – so it never interferes with normal chat clicks (links,
 * player names, run-command components). When it copies a message it consumes the click (returns
 * {@code true}) so no click event also fires.
 */
@Mixin(ChatScreen.class)
public class ChatCopyMixin {

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$copyChat(MouseButtonEvent event, boolean doubled,
                                             CallbackInfoReturnable<Boolean> cir) {
        if (!ConfigManager.getInstance().get().chatOptions.copyChatToClipboard) {
            return;
        }
        if (event.button() != 0 || !event.hasControlDown()) {
            return; // only CTRL + left-click
        }
        if (ChatCopy.copyMessageAt(event.x(), event.y())) {
            cir.setReturnValue(true);
        }
    }

    /**
     * Diana's Sphinx riddle: a plain left-click gives the answer the solver worked out.
     *
     * <p>Deliberately <b>below</b> the copy hook and gated on a plain left button with no modifier,
     * so CTRL + click still copies and never answers. It is armed only in the seconds after a riddle
     * has been solved and only when the player switched it on - see {@code SphinxAnswers} for why
     * that setting exists at all and how narrowly it is fenced. When it fires it consumes the click,
     * because a click that has just sent a command must not also activate whatever it landed on.
     */
    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$sphinxAnswer(MouseButtonEvent event, boolean doubled,
                                                 CallbackInfoReturnable<Boolean> cir) {
        if (event.button() != 0 || event.hasControlDown()) {
            return;
        }
        // Fenced like every other Diana entry point: a throw here would come out of the chat screen's
        // click handler. While the tracker is disabled the click is simply not consumed.
        if (sbs.modid.client.combat.diana.logic.DianaGuard.call(
                sbs.modid.client.combat.diana.logic.DianaGuard.Hook.SPHINX_CLICK, "chat click",
                () -> sbs.modid.client.combat.diana.logic.SphinxAnswers.getInstance().onChatScreenClick(),
                false)) {
            cir.setReturnValue(true);
        }
    }
}
