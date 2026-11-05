/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.dev.scanner;

/**
 * What a Server Scanner session can record. {@link #MENUS} and {@link #MARK} are always on; the rest
 * are chosen with {@code /sbs scan start <channels>} or a {@link ScanPreset}.
 *
 * <p>Each channel carries its own budget ({@link ScanBudget}): how many events a second it may write
 * and how many MB in total, so one noisy channel is throttled - with a {@code throttled} event, never
 * silently - instead of ending the whole session. {@code 0} means no limit of that kind. Pure.
 */
public enum ScanChannel {
    MENUS("menus", 0, 0),
    MARK("mark", 0, 0),
    CHAT("chat", 40, 20),
    ACTIONBAR("actionbar", 10, 10),
    SCOREBOARD("scoreboard", 10, 10),
    TABLIST("tablist", 10, 20),
    /** Nearby entities: spawn, remove, name and equipment diffs. */
    ENTITY("entity", 60, 40),
    /** The player's attacks, damage splashes near the target, kills. */
    DAMAGE("damage", 40, 20),
    SOUND("sound", 30, 20),
    /** Title, subtitle and boss bars. */
    TITLE("title", 10, 5),
    /** Player inventory slot diffs and item pickups. */
    INVENTORY("inventory", 30, 20),
    /** Aggregated per type per second - never per particle. Off in {@code full} unless asked for. */
    PARTICLES("particles", 40, 20),
    /** Block updates near the player. Off unless a preset or the channel word asks for it. */
    BLOCKS("blocks", 60, 30),
    /** World/island change, respawn, death, teleport. */
    WORLD("world", 10, 5),
    /** Cursor position at screen changes and Keep Mouse Position guard events - never per move. */
    MOUSE("mouse", 30, 5);

    public final String key;
    public final int perSecond;
    public final int maxMb;

    ScanChannel(String key, int perSecond, int maxMb) {
        this.key = key;
        this.perSecond = perSecond;
        this.maxMb = maxMb;
    }

    /** Channels that cannot be switched off. */
    public boolean always() {
        return this == MENUS || this == MARK;
    }

    /** The channel named {@code key}, or {@code null}. */
    public static ScanChannel of(String key) {
        for (ScanChannel channel : values()) {
            if (channel.key.equals(key)) {
                return channel;
            }
        }
        return null;
    }

    /** {@code chat, actionbar, ...} - every channel that can be chosen, for help text. */
    public static String choosable() {
        StringBuilder out = new StringBuilder();
        for (ScanChannel channel : values()) {
            if (!channel.always()) {
                out.append(out.isEmpty() ? "" : ", ").append(channel.key);
            }
        }
        return out.toString();
    }
}
