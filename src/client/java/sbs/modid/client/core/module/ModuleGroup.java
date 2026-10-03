/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.module;

/**
 * The fixed top-level groups of the module list, in display order (the enum order IS the
 * sidebar order). Modules are sorted alphabetically within their group.
 *
 * <p>The order tells a story top to bottom: what you play for (Economy, Skills, Combat,
 * Dungeons), who you play with (Party &amp; Chat), what you play through (Inventory &amp;
 * Items), and finally how it all looks (Interface, Visuals, Wardrobe) plus the general
 * comforts (Quality of Life). A module goes into the group matching its <i>purpose</i>, not its
 * implementation – a HUD overlay about skill XP belongs under Skills, not Interface.
 *
 * <p>{@link #PINNED} is special: its modules render at the very top of the list, before any
 * group and without a group header – used for the Licence Token, which unlocks the whole
 * price API and must never be buried in the alphabet.
 */
public enum ModuleGroup {

    PINNED(""),
    /** Making coins: Bazaar, AH flips, Forge, price data. */
    ECONOMY("Economy"),
    /** Levelling and gathering: farming, fishing, mining, garden, trackers. */
    SKILLS("Skills"),
    /** Fighting mobs outside dungeons: slayers, mob highlight, combat-item helpers. */
    COMBAT("Combat"),
    /** Catacombs: map, room helpers, reward-chest extras. */
    DUNGEONS("Dungeons"),
    /** Playing with others: parties, carries, chat, player lookup. */
    PARTY_CHAT("Party & Chat"),
    /** Items and open menus: tooltips, recipes, storage, slots, inventory extras. */
    INVENTORY_ITEMS("Inventory & Items"),
    /** The SBS look of HUD and menus: custom HUD, scoreboard, theme, texture pack. */
    INTERFACE("Interface & Theme"),
    /** World rendering: brightness, particles, camera, scaling. */
    VISUALS("Visuals"),
    /** How the player themself looks: capes and other cosmetics the client draws on you. */
    WARDROBE("Wardrobe"),
    /** Comforts that fit nowhere else: auto sprint, warp menu, keybinds, timers. */
    QUALITY_OF_LIFE("Quality of Life"),
    DEVELOPER("Developer");

    private final String displayName;

    ModuleGroup(String displayName) {
        this.displayName = displayName;
    }

    /** Header text shown above the group's modules (empty for {@link #PINNED}). */
    public String displayName() {
        return displayName;
    }
}
