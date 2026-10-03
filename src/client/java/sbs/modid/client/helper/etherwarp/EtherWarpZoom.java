/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.etherwarp;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.helper.visual.model.ZoomStrength;

/**
 * Feature 2 of the Ether Warp module: zooms the camera in while sneaking with an etherwarp-capable
 * AOTE/AOTV, so a distant warp target can be aimed at precisely.
 *
 * <p>Completely independent of the target highlight – either can be on without the other. The zoom
 * is derived state, not a stored mode: {@link #apply} recomputes it from the live sneak input and
 * held item every frame, so it ends the instant sneaking stops or the item changes, with nothing to
 * reset and no way to get stuck zoomed in (e.g. if the player is disarmed mid-frame).
 *
 * <p>Applied by {@code CameraFovMixin}; when inactive the FOV is returned untouched, so nothing
 * about normal gameplay changes. Scrolling the wheel while aiming zooms further in or out for that
 * aim, see {@link ZoomStrength}.
 */
public final class EtherWarpZoom {

    private static final ZoomStrength STRENGTH = new ZoomStrength();

    private EtherWarpZoom() {
    }

    /** Whether the zoom should be applied right now. */
    public static boolean active() {
        return ConfigManager.getInstance().get().etherWarp.zoom && EtherWarp.armed();
    }

    /**
     * The field of view to render with: narrowed while the zoom is active, unchanged otherwise.
     *
     * @param fov the field of view vanilla computed
     */
    public static float apply(float fov) {
        SBSConfig.EtherWarpSettings cfg = ConfigManager.getInstance().get().etherWarp;
        if (!cfg.zoom || !EtherWarp.armed()) {
            STRENGTH.reset();
            return fov;
        }
        return fov * STRENGTH.fovFactor(cfg.zoomStrength);
    }

    /** Routes a wheel notch into the zoom. Returns whether the wheel was used up here. */
    public static boolean scroll(double scrollY) {
        return active()
                && STRENGTH.scroll(ConfigManager.getInstance().get().etherWarp.zoomStrength, scrollY);
    }
}
