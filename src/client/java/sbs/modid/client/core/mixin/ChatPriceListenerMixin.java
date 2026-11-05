/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.social.chat.logic.ChatAccess;
import sbs.modid.client.core.chat.ChatDispatch;

/**
 * Feeds every chat line the client displays to {@link ChatDispatch}, which hands it to each chat
 * parser behind that parser's island gate and prefilter (the Bazaar price cache among them).
 *
 * <p>Hooks all three {@link ChatComponent} entry points – server system, client system and player
 * messages – so it fires no matter which channel Hypixel sends "[Bazaar] … Setup!" through (the
 * earlier single-channel hook missed it). Read-only: it never cancels or alters the message.
 *
 * <p>As a side effect each hook registers the live {@link ChatComponent} instance in
 * {@link ChatAccess} – the version-independent chat handle used by the chat-copy feature (the
 * {@code Gui} accessor for it moved between 26.1.2 and 26.2).
 */
@Mixin(ChatComponent.class)
public class ChatPriceListenerMixin {

    @Inject(method = "addServerSystemMessage", at = @At("HEAD"))
    private void skyblockSimplified$serverSystem(Component message, CallbackInfo ci) {
        skyblockSimplified$capture(message);
    }

    @Inject(method = "addClientSystemMessage", at = @At("HEAD"))
    private void skyblockSimplified$clientSystem(Component message, CallbackInfo ci) {
        skyblockSimplified$capture(message);
    }

    @Inject(method = "addPlayerMessage", at = @At("HEAD"))
    private void skyblockSimplified$player(Component message, MessageSignature signature, GuiMessageTag tag,
                                           CallbackInfo ci) {
        skyblockSimplified$capture(message);
    }

    private void skyblockSimplified$capture(Component message) {
        ChatAccess.set((ChatComponent) (Object) this);
        // A line SBS itself is printing is output, not input: no parser reads it. Without this a
        // parser that answers a line can answer its own answer - the Sphinx solver did, recursively,
        // until the client died. See SBSChat.
        if (sbs.modid.client.social.chat.logic.SBSChat.ownLineInFlight()) {
            return;
        }
        // Every parser, in its fixed order, behind its island gate and prefilter: see ChatListeners.
        ChatDispatch.dispatch(message);
    }
}
