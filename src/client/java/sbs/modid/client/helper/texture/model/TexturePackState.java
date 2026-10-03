/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.texture.model;

import net.minecraft.resources.Identifier;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;

/**
 * The functional routing hook for the Texture Pack module.
 *
 * <p>The button state ({@link TexturePackMode}) is persisted in the config; this handler exposes the
 * live check and the redirect logic other systems will call. When {@link #isSbsActive()} is true,
 * {@link #route(Identifier)} rewrites an eligible asset path into the SBS pack namespace so custom
 * textures (added to the project later) transparently replace the vanilla ones. Until those assets
 * exist the mechanism is a no-op passthrough for missing files, but the state check and routing are
 * fully wired and ready.
 */
public final class TexturePackState {

    private TexturePackState() {
    }

    /** The current pack mode from the persisted config. */
    public static TexturePackMode mode() {
        return ConfigManager.getInstance().get().texturePack.packTheme;
    }

    /** Whether the SBS pack routing is currently active. */
    public static boolean isSbsActive() {
        return mode() == TexturePackMode.SBS;
    }

    /**
     * Redirects a vanilla asset {@link Identifier} to its SBS-pack equivalent while the SBS theme is
     * active; otherwise returns it unchanged. The SBS variant lives under the mod's namespace at
     * {@code sbs_pack/<original path>}, so a future resource pack only has to drop files there.
     */
    public static Identifier route(Identifier original) {
        if (original == null || !isSbsActive()) {
            return original;
        }
        return Identifier.fromNamespaceAndPath(SkyblockSimplifiedSBS.MOD_ID, "sbs_pack/" + original.getPath());
    }
}
