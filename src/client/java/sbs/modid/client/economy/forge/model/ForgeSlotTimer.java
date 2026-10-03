/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.forge.model;

import java.util.Locale;

/**
 * One occupied Dwarven Forge slot, as the forge menu last described it.
 *
 * <p>A plain mutable POJO because Gson builds it field by field out of the per-profile file.
 *
 * <p><b>The finish time is wall clock, and it is read, never computed from a recipe.</b> HotM's
 * Quick Forge shortens every duration, and the menu's own time-left already includes it - so
 * {@link #finishAt} is "when we read it" plus "what the menu said was left", and nothing else.
 * The forge runs while the player is offline, which is exactly what a wall-clock end time gets
 * right for free.
 */
public final class ForgeSlotTimer {

    /** The menu slot index. The identity: the forge's slot positions do not move. */
    public int slot = -1;

    /** The item being forged, as the slot's name showed it (colour-stripped). Display only. */
    public String item = "";

    /** How many, from the stack count. */
    public int amount = 1;

    /** When it is (or was) ready, epoch millis. */
    public long finishAt;

    /** When the menu was last read, epoch millis - the "last seen" age on the card. */
    public long readAt;

    /** Whether the finished alert has gone out for this item. */
    public boolean notified;

    /** Gson needs a no-arg constructor. */
    public ForgeSlotTimer() {
    }

    public ForgeSlotTimer(int slot, String item, int amount, long finishAt, long readAt) {
        this.slot = slot;
        this.item = item == null ? "" : item;
        this.amount = Math.max(1, amount);
        this.finishAt = finishAt;
        this.readAt = readAt;
    }

    public boolean valid() {
        return slot >= 0 && finishAt > 0L;
    }

    public long remainingMs(long now) {
        return Math.max(0L, finishAt - now);
    }

    public boolean ready(long now) {
        return now >= finishAt;
    }

    /** "Mithril Plate" or "Refined Diamond x2". */
    public String label() {
        String name = item == null || item.isBlank() ? "Forge slot " + (slot + 1) : item;
        return amount > 1 ? name + " x" + amount : name;
    }

    /**
     * {@code 1d 3h}, {@code 1h 12m}, {@code 4m 10s}, {@code 12s} - two units at most, the way the
     * menu writes it; {@code READY} at zero. Seconds round up, so {@code 0s} never shows while
     * something is still cooking.
     */
    public static String format(long remainingMs) {
        if (remainingMs <= 0L) {
            return "READY";
        }
        long s = (remainingMs + 999L) / 1000L;
        long d = s / 86_400L;
        long h = s % 86_400L / 3_600L;
        long m = s % 3_600L / 60L;
        long sec = s % 60L;
        if (d > 0) {
            return h > 0 ? String.format(Locale.ROOT, "%dd %dh", d, h) : d + "d";
        }
        if (h > 0) {
            return m > 0 ? String.format(Locale.ROOT, "%dh %dm", h, m) : h + "h";
        }
        if (m > 0) {
            return sec > 0 ? String.format(Locale.ROOT, "%dm %ds", m, sec) : m + "m";
        }
        return sec + "s";
    }
}
