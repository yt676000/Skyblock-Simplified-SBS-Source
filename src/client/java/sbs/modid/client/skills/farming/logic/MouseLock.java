/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.farming.logic;

import net.minecraft.network.chat.Component;
import sbs.modid.client.social.chat.logic.SBSChat;
import sbs.modid.client.core.config.ConfigManager;

/**
 * Farming module: <b>Mouse Lock</b>. While active, mouse movement no longer turns the camera
 * (the {@code MouseTurnMixin} cancels {@code MouseHandler.turnPlayer}), so long farming runs on
 * straight tracks can't drift because of an accidental mouse nudge. Clicking, hotbar, movement
 * keys and GUIs stay fully usable - only the camera rotation is frozen.
 *
 * <p>Toggled by the configurable key (Farming module settings), dispatched from
 * {@code CommandKeyMixin} on fresh in-world key presses (no screen open) - the same gate the
 * command keybinds use.
 */
public final class MouseLock {

    private static volatile boolean active;

    private MouseLock() {
    }

    /**
     * True while the camera is frozen (read every mouse-move frame by the mixin).
     *
     * <p>Off a farming island the lock reports inactive rather than being cleared: warping away
     * hands the camera straight back, and warping in restores the lock you set - a lock that
     * silently switched itself off mid-trip would be worse than either.
     */
    public static boolean isActive() {
        return active && sbs.modid.client.skills.SkillIslands.farmingAllowed();
    }

    /** Called for every fresh in-world key press; toggles on the configured key. */
    public static void onKeyPressed(int keyCode) {
        int bound = ConfigManager.getInstance().get().farming.mouseLockKey;
        if (bound == -1 || keyCode != bound
                || !sbs.modid.client.skills.SkillIslands.farmingAllowed()) {
            return;
        }
        active = !active;
        SBSChat.send(Component.literal("Mouse Lock ")
                .withColor(SBSChat.WHITE)
                .append(Component.literal(active ? "ON" : "OFF")
                        .withColor(active ? 0x57D977 : 0xE0605F)));
    }
}
