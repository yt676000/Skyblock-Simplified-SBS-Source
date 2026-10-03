/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.terrain;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

/**
 * How much distance fog is drawn over remembered terrain.
 *
 * <p>Expressed as the <b>amount of fog</b>: 0 is none at all and 100 is untouched vanilla. It used
 * to be the opposite - a "clarity" value where 0 meant full fog - and that is a genuinely bad way to
 * ask the question, because anyone trying to get rid of haze sets the fog slider to zero and on that
 * scale zero was the haziest setting there was.
 *
 * <p>Below 100 the fog planes are pushed outward; at 0 they are moved past anything drawable, which
 * removes the haze rather than relocating it. Scaling alone can never remove it - terrain beyond
 * wherever the fog now starts still washes pale toward the sky colour, which is what "still a bit
 * whiteish" looks like.
 */
public final class FarTerrainFog {

    /** Just above no-fog the planes sit this many times further out than vanilla puts them. */
    private static final float MAX_SCALE = 12.0f;

    private FarTerrainFog() {
    }

    private static SBSConfig.FarTerrainSettings cfg() {
        return ConfigManager.getInstance().get().farTerrain;
    }

    /**
     * How much fog to draw, 0-100, migrating an older config's inverted value the first time it is
     * asked for.
     *
     * <p>The migration has to distinguish "this config predates the field" from "the player chose
     * 0", which a plain {@code int} cannot do - it would read a fresh 0 as the default and quietly
     * turn everyone's fog off, or read a migrated 0 as unmigrated and keep overwriting it. Hence the
     * boxed field: {@code null} is the only thing that means "never set".
     */
    @SuppressWarnings("deprecation")
    public static int fogPercent() {
        SBSConfig.FarTerrainSettings settings = cfg();
        if (settings.distanceFog == null) {
            settings.distanceFog = 100 - Math.max(0, Math.min(100, settings.fogClarity));
            ConfigManager.getInstance().save();
        }
        return Math.max(0, Math.min(100, settings.distanceFog));
    }

    /** Sets the fog amount, 0-100. */
    public static void setFogPercent(int percent) {
        cfg().distanceFog = Math.max(0, Math.min(100, percent));
        ConfigManager.getInstance().save();
    }

    /**
     * Whether the render-distance fog should be removed outright rather than merely pushed back.
     *
     * <p>Only ever the <i>distance</i> fog. Water, lava and blindness live in a different set of
     * fields and are never touched - seeing through a lava bath is a cheat, not a view.
     */
    public static boolean removeDistanceFog() {
        return cfg().mode != FarTerrainMode.OFF && fogPercent() <= 0;
    }

    /**
     * The multiplier to apply to the render-distance fog planes; {@code 1.0} means leave it alone.
     * Always 1.0 while the module is off, so the fog is never touched by a disabled feature, and
     * always 1.0 at zero fog because that case is {@link #removeDistanceFog()}'s.
     */
    public static float distanceScale() {
        if (cfg().mode == FarTerrainMode.OFF) {
            return 1.0f;
        }
        int fog = fogPercent();
        if (fog >= 100 || fog <= 0) {
            return 1.0f;
        }
        return 1.0f + (MAX_SCALE - 1.0f) * ((100 - fog) / 100.0f);
    }
}
