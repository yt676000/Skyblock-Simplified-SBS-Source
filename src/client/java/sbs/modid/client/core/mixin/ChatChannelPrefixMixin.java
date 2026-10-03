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
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import sbs.modid.client.social.chat.logic.ActiveChannel;

/**
 * Puts the active channel's command in front of a typed message.
 *
 * <p>A {@link ModifyVariable} rather than a cancel-and-resend, and that is the whole design: vanilla
 * still does the sending, the history, and everything else it does today. There is exactly one send
 * because there is exactly one call — nothing is queued, retried or resent, and a failure is
 * Hypixel's answer to a message the player's own keystroke produced.
 *
 * <p>Every decision about <i>whether</i> to prefix lives in {@code SendChannel.apply}, including the
 * rule that a message already starting with {@code /} is left alone. Repeating that check here would
 * be a second place for the two to disagree.
 *
 * <p><b>Ordering against {@link ChatScreenMixin}.</b> That mixin injects at the same {@code HEAD} to
 * run SBS's own commands, and mixin does not promise which of the two goes first. It does not matter
 * either way: this only ever transforms a message that does <i>not</i> begin with {@code /}, and
 * that one runs only on messages that do. If this transforms first, the other sees {@code /pc …},
 * asks {@code SBSCommands.tryExecute("pc …")}, finds {@code pc} is not one of its root commands and
 * returns false without touching it.
 */
@Mixin(ChatScreen.class)
public abstract class ChatChannelPrefixMixin {

    @ModifyVariable(method = "handleChatInput", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private String skyblockSimplified$prefixActiveChannel(String message) {
        if (!ActiveChannel.enabled()) {
            return message;
        }
        return ActiveChannel.getInstance().active().apply(message);
    }
}
