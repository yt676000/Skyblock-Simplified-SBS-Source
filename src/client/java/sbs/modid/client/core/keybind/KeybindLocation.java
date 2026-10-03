/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.keybind;

import net.minecraft.client.Minecraft;
import sbs.modid.client.core.location.SkyBlockLocation;

/**
 * Evaluates a {@link CommandKeybind}'s conditions against where the player is.
 *
 * <p>"Where the player is" is not answered here - it comes from
 * {@link SkyBlockLocation}, the single reader of the scoreboard's {@code ⏣} zone line and the tab
 * list's {@code Area:} island line. This class only knows what a keybind wants; the position box is
 * read straight off the player and is cheap enough to leave uncached.
 */
public final class KeybindLocation {

    private KeybindLocation() {
    }

    /**
     * Whether the keybind's conditions (island filter + area box) are met right now. An empty island
     * filter and a disabled area box both pass, so a keybind with no conditions always runs.
     *
     * <p>The filter may name an island or a single zone; {@link SkyBlockLocation#matches} handles
     * both, so an island-wide keybind stays alive in every named zone of its island and a zone
     * keybind still only fires in its zone.
     */
    public static boolean conditionsMet(CommandKeybind keybind) {
        if (!SkyBlockLocation.matches(keybind.islandFilter())) {
            return false;
        }
        AreaFilter area = keybind.area();
        if (area.enabled()) {
            var player = Minecraft.getInstance().player;
            if (player == null || !area.contains(player.blockPosition())) {
                return false;
            }
        }
        return true;
    }
}
