/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.buffs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The active Century Cake buffs, one per stat, each with when it runs out. Pure (time is passed in)
 * and Gson-friendly - {@link CakeBuffStore} persists it per account and SkyBlock profile.
 *
 * <p>The expiry is the eat time plus the duration the eat line printed (48 hours). Whether Hypixel
 * counts that time while the player is offline is not known yet, which is why a reading from the
 * Active Effects menu is meant to override it once its format has been captured.
 */
public final class CakeBuffBook {

    /** One active cake buff. */
    public static final class Buff {
        public String stat;
        public int amount;
        public long expiresAt;

        public Buff() {
        }

        Buff(String stat, int amount, long expiresAt) {
            this.stat = stat;
            this.amount = amount;
            this.expiresAt = expiresAt;
        }
    }

    /** Keyed by stat: one cake per stat, and eating it again only refreshes it. */
    public Map<String, Buff> buffs = new LinkedHashMap<>();

    /** The expiry the "runs out soon" alert last fired for, so it fires once per expiry, restarts included. */
    public long warnedForExpiry;

    /** Books an eat or refresh line: the expiry restarts from {@code now}. */
    public void apply(CakeBuffParser.Eat eat, long now) {
        long expiresAt = now + eat.hours() * 3_600_000L;
        buffs.put(eat.stat(), new Buff(eat.stat(), eat.amount(), expiresAt));
    }

    /** Drops every buff that has run out. Returns whether anything was dropped. */
    public boolean prune(long now) {
        return buffs.values().removeIf(buff -> buff.expiresAt <= now);
    }

    /** The active buffs, soonest to expire first. */
    public List<Buff> active(long now) {
        List<Buff> out = new ArrayList<>();
        for (Buff buff : buffs.values()) {
            if (buff.expiresAt > now) {
                out.add(buff);
            }
        }
        out.sort(Comparator.comparingLong(b -> b.expiresAt));
        return out;
    }

    /** When the first active buff runs out, or -1 when none is active. */
    public long nextExpiry(long now) {
        List<Buff> active = active(now);
        return active.isEmpty() ? -1 : active.get(0).expiresAt;
    }

    /** {@code "47h 12m"}, {@code "12m"}, {@code "<1m"} - the card's remaining-time text. */
    public static String remaining(long expiresAt, long now) {
        long minutes = Math.max(0, (expiresAt - now) / 60_000L);
        if (minutes < 1) {
            return "<1m";
        }
        long hours = minutes / 60;
        return hours > 0 ? hours + "h " + (minutes % 60) + "m" : minutes + "m";
    }
}
