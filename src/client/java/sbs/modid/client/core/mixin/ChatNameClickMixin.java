/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Style;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.social.chat.logic.ChatPlayerNames;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.social.playerviewer.ui.PlayerViewerScreen;

/**
 * Player Viewer: SHIFT + left-click a player's name in chat to open their profile.
 *
 * <p>Hooks {@code ChatScreen.handleComponentClicked(Style, boolean)} rather than the raw mouse
 * event, because vanilla has already done the hard part by the time it is called: it resolved
 * <b>which</b> chat component is under the cursor and hands over its {@link Style}, and its second
 * argument is {@code Minecraft.hasShiftDown()}. So the exact clicked name is available with no hit
 * testing and no line parsing of our own.
 *
 * <p>This deliberately replaces vanilla's shift-click behaviour (pasting the name into the chat box)
 * – but only on actual player names, and only while the setting is on. Anything that is not a name
 * resolves to {@code null} and falls straight through to vanilla, as does every click without shift.
 */
@Mixin(ChatScreen.class)
public abstract class ChatNameClickMixin {

    @Inject(method = "handleComponentClicked", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$openProfileOnShiftClick(Style style, boolean insertionMode,
                                                            CallbackInfoReturnable<Boolean> cir) {
        if (!insertionMode) {
            return; // not a shift-click - leave links and run-command components alone
        }
        if (!ConfigManager.getInstance().get().playerViewer.profileViewer
                || !ConfigManager.getInstance().get().playerViewer.chatNameClick) {
            return;
        }
        String player = ChatPlayerNames.playerFrom(style);
        if (player == null) {
            return; // not a player name - vanilla still gets to insert whatever it was
        }
        Minecraft.getInstance().setScreenAndShow(new PlayerViewerScreen(player));
        cir.setReturnValue(true);
    }
}
