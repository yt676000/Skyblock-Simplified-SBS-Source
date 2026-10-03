/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.visual.logic;

import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig.VisualsSettings;
import sbs.modid.client.core.keybind.Keys;
import sbs.modid.client.helper.visual.model.ZoomStrength;
import sbs.modid.client.helper.visual.model.ZoomTransition;

/**
 * Plain hold-to-zoom (Visuals): while the configured key is held the camera's field of view narrows
 * by the configured {@link ZoomStrength}, exactly like the Ether Warp zoom but without any item/sneak
 * condition. Purely a per-frame FOV rewrite through {@code CameraFovMixin} – no camera state is
 * touched, so releasing the key (or being in any screen) restores the normal view.
 *
 * <p>How far along that change is at any moment is {@link ZoomTransition}'s job: with the animation
 * on, the view glides in and back out over a configurable fraction of a second rather than jumping
 * between the two field-of-view values. The zoom therefore keeps applying for a few frames <i>after</i>
 * the key is released – which is also why the wheel nudge is only dropped once the view is all the
 * way back out, so the fade-out cannot jump to a different zoom level halfway through.
 *
 * <p>Scrolling the wheel while the key is held zooms further in or out for that hold; see
 * {@link ZoomStrength} and {@code MouseScrollZoomMixin}.
 */
public final class ZoomControl {

    private static final ZoomStrength STRENGTH = new ZoomStrength();
    private static final ZoomTransition TRANSITION = new ZoomTransition();

    private ZoomControl() {
    }

    /** Whether the zoom key is bound and currently held while actually playing (no screen open). */
    public static boolean active() {
        // A held zoom key must be ignored while any screen is open - otherwise typing the same
        // letter into chat / a command / a text field zooms the world behind it (e.g. zoom on "C"
        // firing on every "c" you type). Zoom is a gameplay action; it only applies in-world.
        // GuiStateManager reads the real current screen (version-safe, null = playing, includes chat).
        if (GuiStateManager.getInstance().getCurrentScreen() != null) {
            return false;
        }
        VisualsSettings cfg = ConfigManager.getInstance().get().visuals;
        return cfg.zoomKey != 0 && Keys.isDown(cfg.zoomKey);
    }

    /** The field of view to render with: narrowed while the zoom key is held, unchanged otherwise. */
    public static float apply(float fov) {
        VisualsSettings cfg = ConfigManager.getInstance().get().visuals;
        boolean held = active();
        if (!cfg.zoomAnimation) {
            TRANSITION.snap(held);
            if (!held) {
                STRENGTH.reset();
                return fov;
            }
            return fov * STRENGTH.fovFactor(cfg.zoomStrength);
        }
        float blend = TRANSITION.advance(held, cfg.zoomSpeed);
        if (!held && TRANSITION.idle()) {
            STRENGTH.reset();
            return fov;
        }
        // Blend between the untouched view (1x) and the configured strength, so every intermediate
        // frame is a real field of view rather than a step towards one.
        float target = STRENGTH.fovFactor(cfg.zoomStrength);
        return fov * (1f + (target - 1f) * blend);
    }

    /** Routes a wheel notch into the zoom. Returns whether the wheel was used up here. */
    public static boolean scroll(double scrollY) {
        return active() && STRENGTH.scroll(ConfigManager.getInstance().get().visuals.zoomStrength, scrollY);
    }
}
