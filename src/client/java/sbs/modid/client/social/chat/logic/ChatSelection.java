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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import sbs.modid.client.social.chat.model.ChatGeometry;
import sbs.modid.client.core.util.PlainText;

/**
 * Selecting chat messages the way a file manager selects files.
 *
 * <ul>
 *   <li><b>Right-click</b> – copy. With a selection active it copies the whole selection and clears
 *       it; with nothing selected it copies just the message clicked.</li>
 *   <li><b>Ctrl + right-click</b> – toggle one message in or out of the selection, keeping the rest,
 *       and make it the anchor.</li>
 *   <li><b>Shift + right-click</b> – select every message from the anchor to the clicked one.</li>
 * </ul>
 *
 * <p><b>Identity.</b> Messages are tracked by object reference, not by their text: chat repeats
 * itself constantly ("Party > A: gg" twice in a row is two different messages), and matching on text
 * would make one click select both. Reference identity is exactly "this message on screen".
 *
 * <p>Consequently the selection only means anything for messages currently in the chat buffer; it is
 * cleared when the chat closes, which also stops it silently outliving the lines it referred to.
 *
 * <p>Client-thread only (chat input and rendering), so no locking.
 */
public final class ChatSelection {

    /**
     * Selected messages, by identity. A {@code LinkedHashSet} keeps insertion order, but copying
     * re-sorts into chat order anyway – the order you clicked in is not the order you want pasted.
     */
    private static final Set<GuiMessage> SELECTED =
            Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /** The message a shift-range extends from – the last one clicked with ctrl, or plain. */
    private static GuiMessage anchor;

    private ChatSelection() {
    }

    public static boolean isSelected(GuiMessage message) {
        return SELECTED.contains(message);
    }

    public static boolean isEmpty() {
        return SELECTED.isEmpty();
    }

    public static int size() {
        return SELECTED.size();
    }

    /** Forgets the selection. Called when the chat closes so it cannot outlive its messages. */
    public static void clear() {
        SELECTED.clear();
        anchor = null;
    }

    // ------------------------------------------------------------------
    // Explorer-style actions
    // ------------------------------------------------------------------

    /** Ctrl + right-click: add or remove one message, leaving the others alone. */
    public static void toggle(GuiMessage message) {
        if (!SELECTED.remove(message)) {
            SELECTED.add(message);
        }
        anchor = message;
    }

    /**
     * Shift + right-click: select everything between the anchor and {@code message}.
     *
     * <p>With no anchor yet this behaves like a plain ctrl-click, which is what a file manager does
     * when you shift-click before selecting anything.
     */
    public static void selectRange(GuiMessage message) {
        List<GuiMessage> messages = ChatGeometry.messages();
        int to = indexOf(messages, message);
        int from = anchor == null ? -1 : indexOf(messages, anchor);
        if (to < 0) {
            return;
        }
        if (from < 0) {
            SELECTED.add(message);
            anchor = message;
            return;
        }
        for (int i = Math.min(from, to); i <= Math.max(from, to); i++) {
            SELECTED.add(messages.get(i));
        }
    }

    /**
     * Plain right-click: copies. The selection wins when there is one – having built it up, copying
     * a single message instead would throw the work away – otherwise the clicked message is copied.
     *
     * @return the number of messages copied
     */
    public static int copy(GuiMessage clicked) {
        List<GuiMessage> toCopy = SELECTED.isEmpty() ? List.of(clicked) : inChatOrder();
        StringBuilder text = new StringBuilder();
        for (GuiMessage message : toCopy) {
            if (text.length() > 0) {
                text.append('\n');
            }
            // Stripped, like the single-message copy: the clipboard is read outside Minecraft, where
            // a § code is noise nobody can render.
            text.append(PlainText.strip(message.content().getString()));
        }
        if (text.length() == 0) {
            return 0;
        }
        Minecraft.getInstance().keyboardHandler.setClipboard(text.toString());
        int count = toCopy.size();
        clear();
        notifyPlayer(count);
        return count;
    }

    /** The selection sorted oldest-first, which is how it reads when pasted. */
    private static List<GuiMessage> inChatOrder() {
        List<GuiMessage> onScreen = ChatGeometry.messages(); // newest first
        List<GuiMessage> out = new ArrayList<>(SELECTED.size());
        for (int i = onScreen.size() - 1; i >= 0; i--) {
            if (SELECTED.contains(onScreen.get(i))) {
                out.add(onScreen.get(i));
            }
        }
        return out;
    }

    /** Identity lookup – {@code List.indexOf} would match on equals and hit the wrong duplicate. */
    private static int indexOf(List<GuiMessage> messages, GuiMessage needle) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) == needle) {
                return i;
            }
        }
        return -1;
    }

    private static void notifyPlayer(int count) {
        if (Minecraft.getInstance().player == null) {
            return;
        }
        Minecraft.getInstance().player.sendSystemMessage(Component.literal(count == 1
                ? "§7Copied chat message to clipboard."
                : "§7Copied §b" + count + "§7 chat messages to clipboard."));
    }
}
