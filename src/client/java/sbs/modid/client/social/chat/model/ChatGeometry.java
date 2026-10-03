/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.chat.model;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import sbs.modid.client.core.mixin.ChatComponentAccessor;
import sbs.modid.client.social.chat.logic.ChatAccess;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps between chat lines and screen positions, in both directions.
 *
 * <p>Vanilla's own hover math is not public, so it has to be mirrored (via
 * {@link ChatComponentAccessor}). It lives here rather than inside any one feature because two of
 * them need it and need to <b>agree</b>: the click has to land on the same line the highlight is
 * drawn under. Two copies of this arithmetic would be two chances to drift apart, and the symptom
 * would be a selection that highlights one message and copies another.
 *
 * <p>Indices are into {@code trimmedMessages}, which is ordered <b>newest first</b> – index 0 is the
 * bottom line on screen.
 */
public final class ChatGeometry {

    /** Chat is anchored 40px above the bottom of the screen; left margin is 4px (vanilla constants). */
    private static final double BOTTOM_OFFSET = 40.0;
    private static final double LEFT_MARGIN = 4.0;

    private ChatGeometry() {
    }

    /** The live chat, or {@code null} when it is not usable (no lines yet, or not focused). */
    private static ChatComponent chat() {
        ChatComponent chat = ChatAccess.get();
        return chat != null && chat.isChatFocused() ? chat : null;
    }

    /** The visible, wrapped chat lines (newest first), or empty. */
    public static List<GuiMessage.Line> lines() {
        ChatComponent chat = chat();
        if (chat == null) {
            return List.of();
        }
        List<GuiMessage.Line> lines = ((ChatComponentAccessor) chat).skyblockSimplified$trimmedMessages();
        return lines == null ? List.of() : lines;
    }

    /**
     * The index into {@link #lines()} at a screen position, or {@code -1} when the position is not
     * over a chat line. Mirrors vanilla's {@code getMessageLineIndexAt}.
     */
    public static int lineIndexAt(double mouseX, double mouseY) {
        ChatComponent chat = chat();
        if (chat == null) {
            return -1;
        }
        ChatComponentAccessor accessor = (ChatComponentAccessor) chat;
        List<GuiMessage.Line> lines = accessor.skyblockSimplified$trimmedMessages();
        double scale = accessor.skyblockSimplified$getScale();
        int lineHeight = accessor.skyblockSimplified$getLineHeight();
        if (lines.isEmpty() || scale <= 0 || lineHeight <= 0) {
            return -1;
        }
        int guiHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        double chatX = mouseX / scale - LEFT_MARGIN;
        double chatY = (guiHeight - mouseY - BOTTOM_OFFSET) / (scale * lineHeight);

        if (chatX < -LEFT_MARGIN || chatX > Math.floor(accessor.skyblockSimplified$getWidth() / scale)) {
            return -1;
        }
        int visible = Math.min(chat.getLinesPerPage(), lines.size());
        if (chatY < 0 || chatY >= visible) {
            return -1;
        }
        int index = (int) Math.floor(chatY + accessor.skyblockSimplified$chatScrollbarPos());
        return index >= 0 && index < lines.size() ? index : -1;
    }

    /**
     * The screen rectangle {@code {x0, y0, x1, y1}} of a line, or {@code null} when it is scrolled
     * out of view. The exact inverse of {@link #lineIndexAt}, so a highlight lands where a click
     * would.
     */
    public static int[] lineBounds(int index) {
        ChatComponent chat = chat();
        if (chat == null) {
            return null;
        }
        ChatComponentAccessor accessor = (ChatComponentAccessor) chat;
        double scale = accessor.skyblockSimplified$getScale();
        int lineHeight = accessor.skyblockSimplified$getLineHeight();
        if (scale <= 0 || lineHeight <= 0) {
            return null;
        }
        int slot = index - accessor.skyblockSimplified$chatScrollbarPos();
        int visible = Math.min(chat.getLinesPerPage(),
                accessor.skyblockSimplified$trimmedMessages().size());
        if (slot < 0 || slot >= visible) {
            return null; // scrolled off screen
        }
        int guiHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        double rowHeight = scale * lineHeight;
        int bottom = (int) Math.round(guiHeight - BOTTOM_OFFSET - slot * rowHeight);
        int top = (int) Math.round(bottom - rowHeight);
        int right = (int) Math.round(accessor.skyblockSimplified$getWidth() + LEFT_MARGIN * scale);
        return new int[] {0, top, right, bottom};
    }

    /**
     * The distinct messages currently on screen, newest first.
     *
     * <p>A single message can wrap across several lines, so the line list is not the message list –
     * and a range selection has to step through messages, not lines, or dragging over a long message
     * would count it several times.
     */
    public static List<GuiMessage> messages() {
        List<GuiMessage> out = new ArrayList<>();
        GuiMessage previous = null;
        for (GuiMessage.Line line : lines()) {
            GuiMessage parent = line.parent();
            if (parent != previous) {
                out.add(parent);
                previous = parent;
            }
        }
        return out;
    }
}
