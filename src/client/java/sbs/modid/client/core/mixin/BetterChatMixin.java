/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.social.chat.logic.BetterChat;

/**
 * The Better Chat display hook. Injects at the private message-add funnel of {@link ChatComponent},
 * which the public add methods call <b>after</b> the SBS chat parsers ran on the untouched line
 * ({@code ChatPriceListenerMixin} at their HEAD) – so filtering / compacting / restyling here can
 * never desync a tracker. Also skips the whole chat render when "hide when unfocused" is active.
 */
@Mixin(ChatComponent.class)
public abstract class BetterChatMixin {

    @Inject(method = "addMessage", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$betterChat(Component content, MessageSignature signature,
                                               GuiMessageSource source, GuiMessageTag tag, CallbackInfo ci) {
        if (BetterChat.getInstance().onAddMessage((ChatComponent) (Object) this,
                content, signature, source, tag)) {
            ci.cancel();
        }
    }

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$hideChat(GuiGraphicsExtractor graphics, Font font, int tickCount,
                                             int mouseX, int mouseY, ChatComponent.DisplayMode displayMode,
                                             boolean focused, CallbackInfo ci) {
        if (BetterChat.getInstance().shouldHideRender((ChatComponent) (Object) this)) {
            ci.cancel();
        }
    }
}
