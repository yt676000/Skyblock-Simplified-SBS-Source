/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.stacktips;

/**
 * What a stack tip is a number <i>of</i>. Each kind has its own switch on the Item Overlay card, so a
 * player can keep pet levels and drop the rest.
 *
 * <p>{@link #menuBound} kinds only mean anything inside one particular menu - "Farming XXV" is a
 * skill level in the Skills menu and nothing at all in a chest - so they are never read off the
 * player's own inventory or the hotbar.
 */
public enum StackTipKind {
    PET(false),
    MINION(false),
    DUNGEON_PASS(false),
    SKILL(true),
    COLLECTION(true);

    private final boolean menuBound;

    StackTipKind(boolean menuBound) {
        this.menuBound = menuBound;
    }

    public boolean menuBound() {
        return menuBound;
    }
}
