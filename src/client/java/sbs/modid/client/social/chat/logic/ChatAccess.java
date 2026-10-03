/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.chat.logic;

import net.minecraft.client.gui.components.ChatComponent;

/**
 * Version-independent access to the live {@link ChatComponent} instance.
 *
 * <p>On 26.1.2 the chat hangs off {@code Gui#getChat()}, on 26.2 off {@code Gui#hud#getChat()} –
 * a direct call to either breaks single-jar compatibility. Instead, the instance is captured by
 * {@code ChatPriceListenerMixin} (which already hooks every message-add path of
 * {@link ChatComponent}, identical in both versions), so by the time any chat line is visible –
 * and copying requires a visible line – this holder is populated.
 */
public final class ChatAccess {

    private static volatile ChatComponent chat;

    private ChatAccess() {
    }

    public static void set(ChatComponent component) {
        chat = component;
    }

    /** The live chat component, or {@code null} if no chat message has been displayed yet. */
    public static ChatComponent get() {
        return chat;
    }
}
