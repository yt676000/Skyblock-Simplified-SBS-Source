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
import net.minecraft.client.multiplayer.chat.GuiMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.social.chat.model.ChatGeometry;
import sbs.modid.client.social.chat.logic.ChatSelection;
import sbs.modid.client.core.config.ConfigManager;

import java.util.List;

/**
 * Chat Options: selecting and copying chat messages with the right mouse button, the way a file
 * manager selects files.
 *
 * <ul>
 *   <li>right-click – copy (the selection if there is one, else the clicked message),</li>
 *   <li>ctrl + right-click – toggle one message in the selection,</li>
 *   <li>shift + right-click – select the range from the anchor.</li>
 * </ul>
 *
 * <p>Only the <b>right</b> button is touched, so every left-click behaviour – links, run-command
 * components, the shift-click profile lookup, the ctrl-click copy – is untouched. The selection is
 * dropped when the chat closes so it can never refer to lines that have since scrolled away.
 */
@Mixin(ChatScreen.class)
public abstract class ChatSelectionMixin {

    @Unique
    private static final int RIGHT_BUTTON = 1;

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$chatSelection(MouseButtonEvent event, boolean doubled,
                                                  CallbackInfoReturnable<Boolean> cir) {
        if (event.button() != RIGHT_BUTTON
                || !ConfigManager.getInstance().get().chatOptions.chatSelection) {
            return;
        }
        GuiMessage message = skyblockSimplified$messageAt(event.x(), event.y());
        if (message == null) {
            return; // right-clicked something that is not a chat line
        }
        if (event.hasControlDown()) {
            ChatSelection.toggle(message);
        } else if (event.hasShiftDown()) {
            ChatSelection.selectRange(message);
        } else {
            ChatSelection.copy(message);
        }
        cir.setReturnValue(true);
    }

    /** Drops the selection when the chat closes – it refers to lines that may not come back. */
    @Inject(method = "removed", at = @At("HEAD"))
    private void skyblockSimplified$clearSelection(CallbackInfo ci) {
        ChatSelection.clear();
    }

    /** The message behind a screen position, or {@code null}. */
    @Unique
    private static GuiMessage skyblockSimplified$messageAt(double mouseX, double mouseY) {
        int index = ChatGeometry.lineIndexAt(mouseX, mouseY);
        if (index < 0) {
            return null;
        }
        List<GuiMessage.Line> lines = ChatGeometry.lines();
        return index < lines.size() ? lines.get(index).parent() : null;
    }
}
