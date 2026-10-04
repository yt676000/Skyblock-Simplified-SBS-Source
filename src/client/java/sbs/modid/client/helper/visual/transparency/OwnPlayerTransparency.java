/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.visual.transparency;

/**
 * Own Player Transparency (Third Person module): the decisions, without game types so they are
 * unit-tested. The rendering half lives in {@code OwnPlayerTransparencyMixin},
 * {@link TranslucentSubmitCollector} and the render-type swap mixins; see
 * {@code docs/features/own-player-transparency.md}.
 *
 * <p>Only the real local player in the world fades. Not the Loadouts / Armor Sets preview (a
 * {@code RemotePlayer} with your profile - a different entity), and not the inventory paperdoll
 * (the same entity, drawn through the GUI path, which clears the tag again).
 */
public final class OwnPlayerTransparency {

    /** Fully opaque: the tag value that means "leave this render state alone". */
    public static final int OPAQUE = 255;

    public static final int MIN_PERCENT = 10;
    public static final int MAX_PERCENT = 100;
    public static final int STEP_PERCENT = 5;

    /**
     * True while the local player's render state is being submitted with a fade. Render thread
     * only - submission is synchronous, so nothing else submits in between. The render-type swap
     * mixins read it: one boolean check per factory call is the whole cost while off.
     */
    public static boolean scope;

    /** The fade of the state being submitted, read by {@link TranslucentSubmitCollector}. */
    public static int scopeAlpha = OPAQUE;

    private OwnPlayerTransparency() {
    }

    /** A slider value snapped to the 5 % grid and clamped to 10-100. */
    public static int snapPercent(int percent) {
        int snapped = Math.round(percent / (float) STEP_PERCENT) * STEP_PERCENT;
        return Math.max(MIN_PERCENT, Math.min(MAX_PERCENT, snapped));
    }

    /** Opacity in percent to an 8-bit alpha (10 % -> 26, 50 % -> 128, 100 % -> 255). */
    public static int alpha(int percent) {
        return Math.round(snapPercent(percent) * 255f / 100f);
    }

    /**
     * The alpha to tag a render state with: {@link #OPAQUE} unless the feature is on, the entity is
     * the local player itself, and the view allows it.
     *
     * @param enabled        the toggle
     * @param localPlayer    {@code entity == minecraft.player} - false for the Loadouts preview,
     *                       which is another entity carrying your profile
     * @param thirdPersonOnly the "Only in third person" setting
     * @param firstPerson    whether the camera is in first person
     * @param percent        the opacity setting
     */
    public static int alphaFor(boolean enabled, boolean localPlayer, boolean thirdPersonOnly,
                               boolean firstPerson, int percent) {
        if (!enabled || !localPlayer || (thirdPersonOnly && firstPerson)) {
            return OPAQUE;
        }
        return alpha(percent);
    }

    /** {@code argb} with its alpha multiplied by {@code alpha} / 255. */
    public static int multiplyAlpha(int argb, int alpha) {
        int a = (argb >>> 24) * alpha / 255;
        return (a << 24) | (argb & 0x00FFFFFF);
    }
}
