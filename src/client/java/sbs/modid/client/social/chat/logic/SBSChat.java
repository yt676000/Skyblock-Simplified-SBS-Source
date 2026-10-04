/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.chat.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.function.Consumer;

/**
 * Central helper for every client-side chat message SBS sends.
 *
 * <p>Guarantees a single, consistent look across the whole mod (and any future module):
 * the {@code [SBS]} prefix is always painted in the SBS accent color {@code #3FB4FF} using
 * Minecraft's RGB text API ({@link MutableComponent#withColor(int)}), not legacy formatting
 * codes. Callers build a colored {@code body} component (starting with a leading space) and
 * pass it here; the prefix is prepended and the line is shown client-side via the player's
 * own {@code sendSystemMessage} (no packets are sent to the server).
 *
 * <h2>Own lines are not chat input</h2>
 *
 * <p>{@code sendSystemMessage} ends in {@code ChatComponent.addClientSystemMessage}, which the chat
 * listener mixin hooks to feed every SBS chat parser - synchronously, on the same call stack. A line
 * sent here is therefore read by SBS's own parsers before this method returns, and a parser that
 * answers a line it recognises can answer its own answer. That is how the Sphinx solver printed 109
 * ever-longer lines in eight seconds and took the client down (1.0.0-beta.10, 2026-10-04). So the
 * delivery is wrapped in a per-thread flag, {@link #ownLineInFlight()}, and the listener skips its
 * parsers while it is set. The flag is restored rather than cleared, so a nested send cannot reopen
 * the gate for the line around it.
 *
 * <p>This covers lines sent through this class. A few callers still call {@code sendSystemMessage}
 * themselves (the IRC channel, chat waypoints, some command replies); those lines still reach the
 * parsers.
 */
public final class SBSChat {

    /** Accent color of the {@code [SBS]} prefix (matches {@code SBSTheme.ACCENT}). */
    public static final int PREFIX_COLOR = 0x3FB4FF;

    /** Default body color (item names, surrounding text, ...). */
    public static final int WHITE = 0xFFFFFF;

    /** Set while a line sent through this class is being added to the chat, on that thread. */
    private static final ThreadLocal<Boolean> OWN_LINE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** Lines a feature sends in reaction to the game, capped per feature. */
    private static final ChatFloodCap FLOOD_CAP = new ChatFloodCap();

    private SBSChat() {
    }

    /**
     * Whether the chat line being added right now is one SBS sent through this class, on this
     * thread. The chat listener mixin reads it and leaves the line alone, so no SBS parser sees it.
     */
    public static boolean ownLineInFlight() {
        return OWN_LINE.get();
    }

    /** The {@code [SBS]} prefix in the accent color. */
    public static MutableComponent prefix() {
        return Component.literal("[SBS]").withColor(PREFIX_COLOR);
    }

    /** Builds a full line: the prefix followed by {@code body} (which should start with a space). */
    public static MutableComponent line(Component body) {
        return prefix().append(body);
    }

    /** Sends a pre-colored body as a client-side message (prefix prepended). */
    public static void send(Component body) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        deliver(line(body), minecraft.player::sendSystemMessage);
    }

    /** Convenience for a plain white message. */
    public static void send(String text) {
        send(Component.literal(" " + text).withColor(WHITE));
    }

    /**
     * {@link #send(Component)} for a line a feature prints in reaction to chat, a packet or a tick,
     * rather than in answer to a command the player typed.
     *
     * <p>Capped per {@code feature} by {@link ChatFloodCap}: past its limit the rest are dropped
     * and the game log says so once. Command output does not go through here - a help listing is
     * many lines on purpose.
     */
    public static void send(String feature, Component body) {
        if (!FLOOD_CAP.allow(feature, System.currentTimeMillis())) {
            return;
        }
        send(body);
    }

    /**
     * Hands {@code line} to {@code target} with {@link #ownLineInFlight()} set for the duration.
     * Package-private so a test can stand in for the player without a running client.
     */
    static void deliver(Component line, Consumer<Component> target) {
        Boolean outer = OWN_LINE.get();
        OWN_LINE.set(Boolean.TRUE);
        try {
            target.accept(line);
        } finally {
            OWN_LINE.set(outer);
        }
    }
}
