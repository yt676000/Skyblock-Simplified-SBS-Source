/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.texture.model;

/**
 * The Texture Pack routing state.
 *
 * <ul>
 *   <li>{@link #DEFAULT} – use vanilla / normal asset paths.</li>
 *   <li>{@link #SBS} – route eligible asset paths to the SBS custom pack (assets added later).</li>
 *   <li>{@link #HYPIXEL_PLUS} – the player-installed Hypixel+ resource pack is enabled (only offered by
 *       the cycle once {@code UserPack.HYPIXEL_PLUS.isInstalled()}).</li>
 *   <li>{@link #FURFSKY_REBORN} – the player-installed Furfsky Reborn resource pack is enabled (only
 *       offered by the cycle once {@code UserPack.FURFSKY_REBORN.isInstalled()}).</li>
 * </ul>
 */
public enum TexturePackMode {

    DEFAULT("Default"),
    SBS("SBS"),
    HYPIXEL_PLUS("Hypixel+"),
    FURFSKY_REBORN("Furfsky Reborn");

    private final String displayName;

    TexturePackMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public TexturePackMode next() {
        TexturePackMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
