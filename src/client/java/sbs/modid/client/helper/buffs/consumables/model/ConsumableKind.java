/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.buffs.consumables.model;

/**
 * The families of timed consumables, each with the clock it starts on. The alert settings are per
 * kind. The clocks are the starting point only: the God Potion's, the potions' and the cookie's come
 * from community sources (ESTIMATED), the rest are guesses, and a relog corrects any of them per key
 * (see {@code ConsumableBook#clockCheck}).
 */
public enum ConsumableKind {
    GOD_POTION(ConsumableClock.ONLINE_ONLY, true),
    BOOSTER_COOKIE(ConsumableClock.REAL_TIME, false),
    /** "They will pause if your God Potion expires" - the mixin line's own words. */
    MIXIN(ConsumableClock.DEPENDENT, false),
    /** "all active effects have been paused and stored" on entering a dungeon. */
    POTION(ConsumableClock.ONLINE_ONLY, true),
    OTHER(ConsumableClock.ONLINE_ONLY, false);

    /** The key every mixin depends on. */
    public static final String GOD_POTION_KEY = "god_potion";
    public static final String COOKIE_KEY = "booster_cookie";
    /** The cookie's display name; the footer calls it "Cookie Buff", chat "Booster Cookie". */
    public static final String COOKIE_NAME = "Booster Cookie";

    private final ConsumableClock defaultClock;
    private final boolean pausedInDungeon;

    ConsumableKind(ConsumableClock defaultClock, boolean pausedInDungeon) {
        this.defaultClock = defaultClock;
        this.pausedInDungeon = pausedInDungeon;
    }

    public ConsumableClock defaultClock() {
        return defaultClock;
    }

    public boolean pausedInDungeon() {
        return pausedInDungeon;
    }

    /** The kind a bare name belongs to, for names that arrive without context (expiry lines). */
    public static ConsumableKind ofName(String displayName) {
        String key = ConsumableTimer.keyOf(displayName);
        if (key.equals(GOD_POTION_KEY)) {
            return GOD_POTION;
        }
        if (key.equals(COOKIE_KEY) || key.equals("cookie_buff")) {
            return BOOSTER_COOKIE;
        }
        if (key.endsWith("_mixin")) {
            return MIXIN;
        }
        return OTHER;
    }
}
