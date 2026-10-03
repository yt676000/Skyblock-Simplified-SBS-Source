/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.core.command.SBSCommands;

/**
 * Routes client-side commands without any Fabric command API.
 *
 * <p>Injects into {@link ChatScreen#handleChatInput(String, boolean)} – the single
 * entry point the chat screen uses when the player submits text. This is earlier
 * than {@code ClientPacketListener.sendCommand}, so it also catches commands the
 * client would otherwise reject as "unknown" before sending. The {@code message}
 * still contains the leading slash here.
 *
 * <p>If the input is one of our commands we run it via our own Brigadier dispatcher
 * and cancel, so the chat screen never forwards it to the server.
 *
 * <p>Verified against this project's mappings: {@code handleChatInput(String,boolean)}
 * returns {@code void}, hence {@link CallbackInfo}.
 */
@Mixin(ChatScreen.class)
public class ChatScreenMixin {

    @Inject(method = "handleChatInput", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$interceptCommand(String message, boolean addToHistory, CallbackInfo ci) {
        // Crystal Hollows Lobby Closing (opt-in): past the cutoff day a typed leave command is held
        // once and passes on its repeat. First, so nothing below books a leave that did not happen.
        // Never throws; any failure inside lets the command through.
        if (sbs.modid.client.helper.reminder.logic.HollowsLeaveGuard.intercept(message)) {
            ci.cancel();
            return;
        }
        // A lobby the player asked for is not a kick: tell the Rejoin Timer before the command goes
        // out, so its sidebar fallback does not read the resulting lobby as one.
        sbs.modid.client.helper.rejoin.RejoinTimer.getInstance().onCommandSent(message);
        // Layout Recorder (dev): the command word (no arguments) is the root of the click path.
        sbs.modid.client.core.dev.ScreenOpeners.getInstance().onCommand(message);
        if (message != null && message.startsWith("/") && SBSCommands.tryExecute(message.substring(1))) {
            // Vanilla records the sent-message history AFTER this point; since we cancel here, we
            // record it ourselves – arrow-key recall must offer /sbs like any other command.
            if (addToHistory) {
                net.minecraft.client.Minecraft.getInstance().gui.hud.getChat().addRecentChat(message);
            }
            ci.cancel();
        }
    }
}
