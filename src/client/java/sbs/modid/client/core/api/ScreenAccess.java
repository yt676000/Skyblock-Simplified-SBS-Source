/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.api;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;

/**
 * Live read of the currently open screen: a direct call to {@link Gui#screen()}. Kept as a tiny
 * facade (rather than inlining the call everywhere) because {@link GuiStateManager} pairs it with
 * the hook-tracked state as a fallback during early startup, when {@code Minecraft.gui} is not
 * available yet.
 */
public final class ScreenAccess {

    private ScreenAccess() {
    }

    /** True when the live value can be read – its result (including {@code null}) is authoritative. */
    public static boolean available() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft != null && minecraft.gui != null;
    }

    /** The screen currently open, read live from {@code Minecraft.gui}; {@code null} if none. */
    public static Screen current() {
        Minecraft minecraft = Minecraft.getInstance();
        Gui gui = minecraft != null ? minecraft.gui : null;
        return gui != null ? gui.screen() : null;
    }
}
