/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.dragonprofit.logic;

import sbs.modid.client.combat.dragonprofit.model.DragonType;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * One End dragon fight at a time, from your first eye to the end of the loot window. Pure: the
 * clock, your name and the prices are passed in, so the whole fight is a unit test.
 *
 * <p>A fight is <i>pending</i> from your first eye (or the spawn line, if you placed none) and
 * <i>down</i> from "&lt;TYPE&gt; DRAGON DOWN!". Loot gained while down and inside {@link #LOOT_WINDOW_MS}
 * books to it; when the window ends {@link #tick} prices it once and returns the finished
 * {@link Result}. Prices are frozen at that moment so the last fight's line does not drift.
 */
public final class DragonFightLedger {

    /** How long after "DRAGON DOWN!" gains count as this fight's loot. */
    public static final long LOOT_WINDOW_MS = 60_000L;
    public static final String SUMMONING_EYE = "SUMMONING_EYE";

    /** A finished, priced fight. {@code damage}/{@code position} are -1 when the block was missed. */
    public record Result(DragonType type, int eyes, long damage, int position, long dropValue, long eyeCost,
                         String bestDropId, long bestDropValue, boolean eyeDropped, Map<String, Integer> drops) {

        public long profit() {
            return dropValue - eyeCost;
        }
    }

    private DragonType type;
    private int eyes;
    private long damage = -1L;
    private int position = -1;
    private boolean eyeDropped;
    private boolean pending;
    private long downAt = -1L;
    private final Map<String, Integer> drops = new LinkedHashMap<>();

    /** True between your first eye / the spawn and the end of the loot window. */
    public boolean active() {
        return pending;
    }

    /** True from "DRAGON DOWN!" until the loot window closes. */
    public boolean down() {
        return downAt >= 0;
    }

    public int eyes() {
        return eyes;
    }

    public DragonType type() {
        return type;
    }

    /** True while the dragon is down and the loot window is open. */
    public boolean lootWindowOpen(long now) {
        return downAt >= 0 && now - downAt < LOOT_WINDOW_MS;
    }

    /** One parsed line. {@code self} is your name; eye and damager lines naming others are ignored. */
    public void onEvent(DragonParser.Event event, String self) {
        onEvent(event, self, System.currentTimeMillis());
    }

    public void onEvent(DragonParser.Event event, String self, long now) {
        switch (event.type()) {
            case EYE_PLACED -> {
                // "You placed a Summoning Eye!" is how Hypixel prints your own eye (2026-10-18:
                // four of them read as someone called "You", and the fight booked 0 eyes, 0 cost).
                if (self != null && (self.equalsIgnoreCase(event.name()) || "You".equals(event.name()))) {
                    if (down()) {
                        return;     // a new fight's eye while the last one's loot is open - not ours
                    }
                    pending = true;
                    eyes++;
                }
            }
            case SPAWN -> {
                if (!down()) {
                    pending = true;
                    type = event.dragon();
                }
            }
            case DOWN -> {
                if (pending && !down()) {
                    type = event.dragon();
                    downAt = now;
                }
            }
            case OWN_DAMAGE -> {
                if (down()) {
                    damage = event.damage();
                    position = event.count();
                }
            }
            case EYE_DROP -> {
                if (down()) {
                    eyeDropped = true;
                }
            }
            default -> {
            }
        }
    }

    /** An item you gained (inventory or sack). Books only dragon loot, only inside the window. */
    public void onGain(String itemId, int count, long now) {
        if (count <= 0 || !lootWindowOpen(now) || !isDragonLoot(itemId)) {
            return;
        }
        drops.merge(itemId, count, Integer::sum);
    }

    /**
     * Closes the fight once its loot window has passed.
     *
     * @param price an item id's unit price, {@code null} when unknown (counts as 0)
     * @return the finished fight, or {@code null} while it is still running
     */
    public Result tick(long now, Function<String, Long> price) {
        if (!down() || lootWindowOpen(now)) {
            return null;
        }
        return finish(price);
    }

    /** Closes a downed fight now (leaving The End early); a fight that never went down is dropped. */
    public Result leave(Function<String, Long> price) {
        if (!down()) {
            reset();
            return null;
        }
        return finish(price);
    }

    private Result finish(Function<String, Long> price) {
        long value = 0L;
        String bestId = null;
        long bestValue = 0L;
        for (Map.Entry<String, Integer> e : drops.entrySet()) {
            long each = orZero(price.apply(e.getKey())) * e.getValue();
            value += each;
            if (bestId == null || each > bestValue) {
                bestId = e.getKey();
                bestValue = each;
            }
        }
        long cost = eyes * orZero(price.apply(SUMMONING_EYE));
        Result result = new Result(type, eyes, damage, position, value, cost, bestId, bestValue, eyeDropped,
                Map.copyOf(drops));
        reset();
        return result;
    }

    private void reset() {
        type = null;
        eyes = 0;
        damage = -1L;
        position = -1;
        eyeDropped = false;
        pending = false;
        downAt = -1L;
        drops.clear();
    }

    /**
     * What an End dragon can drop: the eight fragments, the dragon armour pieces, the Aspect of the
     * Dragons, claw / horn / scale and a Summoning Eye. A whitelist, so a Zealot drop or a bought
     * item in the same minute never counts as dragon loot.
     */
    public static boolean isDragonLoot(String id) {
        if (id == null) {
            return false;
        }
        String upper = id.toUpperCase(Locale.ROOT);
        if (upper.equals(SUMMONING_EYE) || upper.equals("HOLY_FRAGMENT") || upper.equals("ASPECT_OF_THE_DRAGON")
                || upper.equals("DRAGON_CLAW") || upper.equals("DRAGON_HORN") || upper.equals("DRAGON_SCALE")) {
            return true;
        }
        for (DragonType t : DragonType.values()) {
            if (upper.equals(t.fragmentId()) || upper.startsWith(t.name() + "_DRAGON_")) {
                return true;
            }
        }
        return false;
    }

    private static long orZero(Long value) {
        return value == null ? 0L : value;
    }
}
