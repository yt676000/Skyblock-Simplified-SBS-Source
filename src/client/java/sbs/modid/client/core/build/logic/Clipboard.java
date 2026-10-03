/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.build.logic;

import sbs.modid.client.core.build.model.Schematic;

/**
 * The last thing copied or loaded - what {@code /.. save} writes and {@code /.. paste} shows.
 *
 * <p>Garden Blueprint's copy key fills it too, so a plot copy can be saved to the library with the
 * same command as anything else. Memory only, one slot.
 */
public final class Clipboard {

    private static volatile Schematic content;

    private Clipboard() {
    }

    public static Schematic get() {
        return content;
    }

    public static void set(Schematic value) {
        content = value;
    }

    public static void clear() {
        content = null;
    }
}
