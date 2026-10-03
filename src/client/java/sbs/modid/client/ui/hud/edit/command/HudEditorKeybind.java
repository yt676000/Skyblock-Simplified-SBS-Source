/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.hud.edit.command;

import net.minecraft.client.Minecraft;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.ui.hud.edit.ui.HudEditorScreen;

/**
 * Opens either GUI editor from its configurable in-world hotkey (both unbound by default; set in the
 * GUI module's settings).
 *
 * <ul>
 *   <li><b>Edit GUI</b> – every HUD element there is.</li>
 *   <li><b>Edit GUI (visible only)</b> – just the cards the HUD actually drew in the last frame.
 *       This is the one that gains the most from being a hotkey: the visible set is sampled from the
 *       frame before the editor opens, so pressing it out in the world - in the Garden, mid-dungeon,
 *       on a slayer boss - is exactly when it lists what you want to nudge.</li>
 * </ul>
 *
 * <p>Dispatched from {@code CommandKeyMixin}, which only fires on a fresh press with no screen open
 * – the same gate every other SBS hotkey uses, so the key can never fire while typing in chat or
 * browsing a menu. The editor saves through its own "Save &amp; Exit" button as usual.
 */
public final class HudEditorKeybind {

    private HudEditorKeybind() {
    }

    /** Called for every fresh in-world key press. */
    public static void onKeyPressed(int keyCode) {
        if (keyCode == 0) {
            return;   // "unbound" is 0, and an unbound key must never match a press
        }
        var gui = ConfigManager.getInstance().get().hypixelGui;
        if (keyCode == gui.editGuiKey) {
            Minecraft.getInstance().setScreenAndShow(new HudEditorScreen());
        } else if (keyCode == gui.editGuiVisibleKey) {
            // Bound to the same key as the full editor, the full one wins - never open both, and
            // never leave the player with a key that does nothing.
            Minecraft.getInstance().setScreenAndShow(HudEditorScreen.activeOnly());
        }
    }
}
