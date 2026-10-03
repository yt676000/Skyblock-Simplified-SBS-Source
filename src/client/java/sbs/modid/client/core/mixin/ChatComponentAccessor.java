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
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/**
 * Exposes the private {@link ChatComponent} internals the chat-copy feature needs to hit-test which
 * message line is under the cursor, mirroring vanilla's own (now non-public) hover math. Read-only
 * for the copy feature; the Better Chat compactor additionally reads {@code allMessages} and re-adds
 * a transformed message through the private {@code addMessage} / {@code refreshTrimmedMessages}.
 */
@Mixin(ChatComponent.class)
public interface ChatComponentAccessor {

    @Accessor("trimmedMessages")
    List<GuiMessage.Line> skyblockSimplified$trimmedMessages();

    @Accessor("chatScrollbarPos")
    int skyblockSimplified$chatScrollbarPos();

    @Invoker("getScale")
    double skyblockSimplified$getScale();

    @Invoker("getLineHeight")
    int skyblockSimplified$getLineHeight();

    @Invoker("getWidth")
    int skyblockSimplified$getWidth();

    // ---- Better Chat (compactor counter + transform re-add) ------------------------------------

    @Accessor("allMessages")
    List<GuiMessage> skyblockSimplified$allMessages();

    @Invoker("refreshTrimmedMessages")
    void skyblockSimplified$refreshTrimmedMessages();

    @Invoker("addMessage")
    void skyblockSimplified$addMessage(Component content, MessageSignature signature,
                                       GuiMessageSource source, GuiMessageTag tag);
}
