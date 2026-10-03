/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.terrain;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.keybind.Keys;

/**
 * One key that switches Far Terrain off and back on, and says what it did.
 *
 * <p>This is the module whose cost shows up as framerate, so it is the one worth being able to drop
 * the instant a fight gets busy - without opening a menu, finding the row and reading a slider. The
 * key flips between Off and <b>whatever mode was in use</b>, so a player running Performance mode
 * gets Performance back rather than being silently upgraded to On.
 *
 * <p><b>Why a toast and not the action bar.</b> The obvious place for a one-line confirmation is
 * {@code setOverlayMessage}, above the hotbar - but on SkyBlock that line belongs to Hypixel, which
 * rewrites it with health and mana several times a second, so the message would be gone before it
 * was read. A toast is drawn in the corner by the game itself, lasts long enough to notice and is
 * nobody else's to overwrite. {@code addOrUpdate} rather than {@code add} so mashing the key
 * replaces the popup instead of stacking a column of them.
 */
public final class FarTerrainToggle {

    /** Our own toast slot, so an update replaces this popup and never one of Minecraft's. */
    private static final SystemToast.SystemToastId TOAST_ID = new SystemToast.SystemToastId();

    private static boolean wasDown;

    /** The mode to come back to. Switching off must not cost a player their Performance setting. */
    private static FarTerrainMode lastEnabled = FarTerrainMode.ON;

    private FarTerrainToggle() {
    }

    private static SBSConfig.FarTerrainSettings cfg() {
        return ConfigManager.getInstance().get().farTerrain;
    }

    /** Driven every client tick from {@link FarTerrainManager#tick}, in every mode including Off. */
    public static void tick(Minecraft minecraft) {
        int key = cfg().toggleKey;
        if (key == 0) {
            wasDown = false;
            return;
        }
        boolean down = Keys.isDown(key);
        if (down == wasDown) {
            return;
        }
        wasDown = down;
        if (!down) {
            return;   // act on the press, so holding the key does not flip it every tick
        }
        // Swallowed rather than deferred while a screen is open: the same letter typed into chat
        // must not toggle the module, and must not fire the moment the screen closes either.
        if (GuiStateManager.getInstance().getCurrentScreen() != null) {
            return;
        }
        toggle(minecraft);
    }

    private static void toggle(Minecraft minecraft) {
        SBSConfig.FarTerrainSettings settings = cfg();
        if (settings.mode == FarTerrainMode.OFF) {
            settings.mode = lastEnabled == FarTerrainMode.OFF ? FarTerrainMode.ON : lastEnabled;
        } else {
            lastEnabled = settings.mode;
            settings.mode = FarTerrainMode.OFF;
        }
        ConfigManager.getInstance().save();
        // The slider's ceiling follows the module; doing it here means the popup already reports the
        // distance that is actually in force rather than the one from a moment ago.
        FarTerrainRenderDistance.sync(settings.mode != FarTerrainMode.OFF);
        announce(minecraft);
    }

    /** Shows the popup describing the state the module is in right now. */
    public static void announce(Minecraft minecraft) {
        if (minecraft.gui == null) {
            return;
        }
        SystemToast.addOrUpdate(minecraft.gui.toastManager(), TOAST_ID,
                Component.literal("Far Terrain"), Component.literal(currentSetting()));
    }

    /** The one-line summary the popup shows: the mode, and what it means for what you will see. */
    public static String currentSetting() {
        SBSConfig.FarTerrainSettings settings = cfg();
        if (settings.mode == FarTerrainMode.OFF) {
            return "Off - remembered terrain unloaded";
        }
        String line = settings.mode.displayName() + " - " + FarTerrainRenderDistance.current()
                + " chunks";
        if (settings.uncapped) {
            line += ", uncapped";
        }
        return line;
    }
}
