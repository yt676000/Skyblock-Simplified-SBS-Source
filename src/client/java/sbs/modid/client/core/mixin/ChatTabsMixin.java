/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.social.chat.logic.ChatTabs;

/**
 * Chat Tabs: shows one chat channel at a time without losing any of the others.
 *
 * <p>The filter sits on {@code addMessageToDisplayQueue} — the wrapping step that turns a stored
 * message into the lines that are drawn — and <b>not</b> on {@code addMessage}. That is the whole
 * design: the message is still logged, still stored in {@code allMessages} and has already passed
 * every SBS parser, so switching tab re-runs vanilla's {@code refreshTrimmedMessages} over the full
 * history and everything the previous tab hid comes straight back. Filtering at arrival instead
 * would make each switch lossy and each hidden line gone for good.
 *
 * <p>Unread counting hangs off {@code addMessageToQueue}, which runs once per genuinely new message;
 * the display queue is also rebuilt on a tab switch and on a resize, where counting would inflate
 * the badges every time the chat is redrawn.
 */
@Mixin(ChatComponent.class)
public abstract class ChatTabsMixin {

    @Inject(method = "addMessageToDisplayQueue", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$filterByTab(GuiMessage message, CallbackInfo ci) {
        if (message != null && !ChatTabs.getInstance().isVisible(message.content())) {
            ci.cancel();
        }
    }

    @Inject(method = "addMessageToQueue", at = @At("HEAD"))
    private void skyblockSimplified$countUnread(GuiMessage message, CallbackInfo ci) {
        if (message != null) {
            ChatTabs.getInstance().onMessageStored(message.content());
        }
    }

    /**
     * The history a tab was filtering is gone — a rejoin, a server hop, a manual clear — so the
     * selection goes back to the default rather than leaving the next session quietly filtered.
     */
    @Inject(method = "clearMessages", at = @At("HEAD"))
    private void skyblockSimplified$resetTab(boolean history, CallbackInfo ci) {
        ChatTabs.getInstance().reset();
    }
}
