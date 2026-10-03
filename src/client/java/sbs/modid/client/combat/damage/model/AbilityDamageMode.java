/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.damage.model;

/**
 * Where Hypixel's ability-damage lines ("Your Implosion hit 1 enemy for 1,394,599.3 damage.") go.
 *
 * <ul>
 *   <li>{@link #CHAT} – vanilla: the line stays in chat, nothing is parsed or drawn.</li>
 *   <li>{@link #HIDDEN} – the line is removed from chat and nothing replaces it.</li>
 *   <li>{@link #HUD} – the line is removed from chat and listed on the movable Ability Damage
 *       card instead, so the numbers stay readable without the chat scrolling away.</li>
 * </ul>
 */
public enum AbilityDamageMode {

    CHAT("Keep in chat"),
    HIDDEN("Hide"),
    HUD("HUD card");

    private final String displayName;

    AbilityDamageMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public AbilityDamageMode next() {
        AbilityDamageMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
