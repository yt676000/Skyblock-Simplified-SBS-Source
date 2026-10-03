/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.chat.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;

import java.util.List;
import sbs.modid.client.social.chat.model.ChatGeometry;
import sbs.modid.client.core.util.PlainText;

/**
 * Copies the clean text of the chat message under the cursor to the system clipboard.
 *
 * <p>The line-under-the-cursor lookup lives in {@link ChatGeometry}, shared with the chat selection
 * so the two can never disagree about which message a click landed on. This resolves that line back
 * to its source {@link GuiMessage} and copies its text with the {@code §} codes taken out
 * ({@link PlainText}) – a clipboard is for pasting somewhere that is not Minecraft, and
 * {@code §aYou have §a6K§a hours} is not what anybody meant to copy. Uses only
 * {@code net.minecraft.*} – the clipboard goes through {@code KeyboardHandler}, no AWT / Fabric.
 */
public final class ChatCopy {

    private ChatCopy() {
    }

    /**
     * Attempts to copy the chat message at {@code (mouseX, mouseY)} to the clipboard.
     *
     * @return {@code true} if a message was found and copied (the click should then be consumed).
     */
    public static boolean copyMessageAt(double mouseX, double mouseY) {
        Minecraft minecraft = Minecraft.getInstance();
        int index = ChatGeometry.lineIndexAt(mouseX, mouseY);
        List<GuiMessage.Line> lines = ChatGeometry.lines();
        if (index < 0 || index >= lines.size()) {
            return false;
        }
        Component message = lines.get(index).parent().content();
        String text = PlainText.strip(message.getString());
        if (text.isEmpty()) {
            return false;
        }
        minecraft.keyboardHandler.setClipboard(text);
        if (minecraft.player != null) {
            minecraft.player.sendSystemMessage(Component.literal("Copied chat message to clipboard."));
        }
        return true;
    }
}
