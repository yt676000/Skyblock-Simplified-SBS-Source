/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.cooldowns;

import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mirrors the vanilla item-cooldown clock so the HUD can show remaining <b>seconds</b> – the
 * vanilla {@code ItemCooldowns} only exposes a percentage and keeps its entries private. Fed by
 * {@code ItemCooldownsMixin}: every {@code addCooldown} records the end tick against our own tick
 * counter (advanced from the vanilla {@code tick()}).
 */
public final class CooldownTracker {

    private static final CooldownTracker INSTANCE = new CooldownTracker();

    private final Map<Identifier, Integer> endTicks = new ConcurrentHashMap<>();
    private volatile int tickCount;

    private CooldownTracker() {
    }

    public static CooldownTracker getInstance() {
        return INSTANCE;
    }

    public void onTick() {
        tickCount++;
    }

    public void onAdd(Identifier group, int durationTicks) {
        if (group != null && durationTicks > 0) {
            endTicks.put(group, tickCount + durationTicks);
        }
    }

    public void onRemove(Identifier group) {
        if (group != null) {
            endTicks.remove(group);
        }
    }

    /** Remaining cooldown for a group in seconds, or {@code -1} when none. */
    public double remainingSeconds(Identifier group) {
        if (group == null) {
            return -1;
        }
        Integer end = endTicks.get(group);
        if (end == null) {
            return -1;
        }
        int remaining = end - tickCount;
        if (remaining <= 0) {
            endTicks.remove(group);
            return -1;
        }
        return remaining / 20.0;
    }
}
