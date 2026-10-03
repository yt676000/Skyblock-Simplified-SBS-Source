/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.social.chat.logic.ChatTabs;
import sbs.modid.client.social.chat.model.ChatTab;
import sbs.modid.client.social.chat.render.ChatTabBar;

/**
 * Chat Tabs: switching channel by clicking the strip.
 *
 * <p>Only the click lives here. The strip itself is drawn from the HUD ({@code HudMixin}), because
 * the HUD's own chat hook does not run while the chat is focused - hanging the tabs off it would
 * have made them vanish at the exact moment they became clickable.
 *
 * <p>The click is ignored while the command-suggestion popup is open: that popup grows upward over
 * the same band and is offered the click first by vanilla, so a tab underneath it is a tab clicked
 * by accident. {@link ChatTabBar#tabAt} maps the position through the HUD transform, so this keeps
 * working wherever the player has dragged or scaled the strip.
 */
@Mixin(ChatScreen.class)
public abstract class ChatTabBarMixin {

    @Unique
    private static final int LEFT_BUTTON = 0;

    @Shadow
    private CommandSuggestions commandSuggestions;

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$clickTab(MouseButtonEvent event, boolean doubled,
                                             CallbackInfoReturnable<Boolean> cir) {
        if (event.button() != LEFT_BUTTON
                || !ChatTabBar.visible()
                || (commandSuggestions != null && commandSuggestions.isVisible())) {
            return;
        }
        ChatTab tab = ChatTabBar.tabAt(event.x(), event.y());
        if (tab != null) {
            ChatTabs.getInstance().select(tab);
            cir.setReturnValue(true);
        }
    }
}
