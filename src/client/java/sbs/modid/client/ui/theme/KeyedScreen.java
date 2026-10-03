/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.theme;

/**
 * An SBS screen with a stable identity for per-screen settings such as its own opacity.
 *
 * <p>The id is a declared constant, never derived from the class: renaming or moving the class must
 * not lose what the player set for it. Lower-case, {@code [a-z0-9_]}.
 */
public interface KeyedScreen {

    /** This screen's stable id - {@code "config"}, {@code "hud_editor"}. Never changes once shipped. */
    String screenId();
}
