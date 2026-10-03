/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.foraging.model;

import sbs.modid.client.core.util.PlainText;

import java.util.List;

/**
 * One slot of a beacon tuning menu, as much of it as the solver has any use for.
 *
 * <p><b>Why the menu is copied out at all.</b> The tuning parse is guesswork about wording until
 * somebody has stood at a beacon with it, which is exactly the kind of code that has to be runnable
 * on a bench: a record of plain strings can be built in a test from the lines Hypixel is believed to
 * send, so the rules can be pinned down and corrected without a trip to Galatea. Everything
 * Minecraft-shaped stops at {@code BeaconTuning}, which is what fills these in.
 *
 * <p>Text arrives the way the server sends it - {@code §}-coded - and is stripped here through
 * {@link PlainText}, so no caller has to remember to and no two of them can disagree about how.
 *
 * @param slot   index in the menu's own slots (the player inventory is never included)
 * @param name   the item's display name, colour codes removed
 * @param itemId the item's registry path ({@code light_blue_stained_glass_pane}), lower case
 * @param glint  whether the stack carries the enchant glint, which menus use to mark a current pick
 * @param lore   the lore lines, colour codes removed
 */
public record BeaconPanel(int slot, String name, String itemId, boolean glint, List<String> lore) {

    public BeaconPanel {
        name = PlainText.strip(name).trim();
        itemId = itemId == null ? "" : itemId;
        List<String> plain = lore == null ? List.of() : lore.stream()
                .map(line -> PlainText.strip(line).trim())
                .toList();
        lore = plain;
    }
}
