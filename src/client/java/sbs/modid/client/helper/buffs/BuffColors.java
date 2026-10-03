/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.buffs;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.render.OverlayColor;

/**
 * The four colours the two buff readouts are written in: a name and a time for each buff, chosen by
 * the player and read here by everything that draws one.
 *
 * <p>One place because there are two drawings of the same two facts - the Custom Scoreboard rows and
 * the HUD cards - and they have already drifted once: both carried private copies of a lavender and
 * an amber that appear nowhere in SkyBlock, so the rows did not look like the buffs they name. A
 * colour that two files each hold their own literal for is a colour that will disagree with itself.
 *
 * <p>The defaults are read off Hypixel's own screens rather than picked: the Booster Cookie's item
 * name is legendary gold and its duration line is green, the God Potion's name is red and its
 * remaining time is light purple. They are only defaults - every one of the four is a colour row in
 * the Active Buffs settings, so a player who wants them to match a theme instead can say so.
 *
 * <p>Returned as {@code RRGGBB} with no alpha. The scoreboard wants exactly that for
 * {@code TextColor.fromRgb}; a HUD caller drawing text adds its own {@code 0xFF000000}.
 */
public final class BuffColors {

    /** Hypixel writes the God Potion's name in red. */
    public static final int GOD_POTION_NAME_DEFAULT = 0xFF5555;
    /** ...and the time it has left in light purple. */
    public static final int GOD_POTION_TIME_DEFAULT = 0xFF55FF;
    /** The Booster Cookie is a legendary item, so its name is gold. */
    public static final int COOKIE_BUFF_NAME_DEFAULT = 0xFFAA00;
    /** Its "Duration:" line is green. */
    public static final int COOKIE_BUFF_TIME_DEFAULT = 0x55FF55;

    private BuffColors() {
    }

    private static SBSConfig.BuffsSettings cfg() {
        return ConfigManager.getInstance().get().buffs;
    }

    public static int godPotionName() {
        return resolve(cfg().godPotionNameHex, GOD_POTION_NAME_DEFAULT);
    }

    public static int godPotionTime() {
        return resolve(cfg().godPotionTimeHex, GOD_POTION_TIME_DEFAULT);
    }

    public static int cookieBuffName() {
        return resolve(cfg().cookieBuffNameHex, COOKIE_BUFF_NAME_DEFAULT);
    }

    public static int cookieBuffTime() {
        return resolve(cfg().cookieBuffTimeHex, COOKIE_BUFF_TIME_DEFAULT);
    }

    /**
     * The stored hex, or the default when there is none to read.
     *
     * <p>An empty field is how the colour rows say "unset", and an unparseable one is a hand-edited
     * config. Both answer with the default rather than with black: a row nobody can read looks like
     * a broken feature, and the player has no way to tell it apart from one.
     */
    private static int resolve(String hex, int fallback) {
        Integer parsed = OverlayColor.parseHex(hex);
        return parsed == null ? fallback : parsed & 0xFFFFFF;
    }
}
