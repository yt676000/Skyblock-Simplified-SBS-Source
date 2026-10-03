/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.module;

/**
 * The optional second level of the settings sidebar: a family of modules inside one
 * {@link ModuleGroup}, drawn as a small foldable sub-header with its modules under it.
 *
 * <p><b>Why it exists.</b> Skills was one flat A–Z list of thirty cards across six skill families,
 * and most card names do not say which family they belong to - <i>Sweep</i>, <i>Honey</i> and
 * <i>Beacon Tuner</i> are all Foraging. Unfolding Skills put Foraging cards between Garden and Mining
 * ones, which a player reported as "foraging stuff shows up when I never picked Foraging".
 *
 * <p><b>Declaration order is sidebar order</b> inside a group, the same rule {@link ModuleGroup}
 * follows. Each constant names its owning group; {@link ModuleManager} drops a subgroup declared by a
 * module of a different group rather than let a Foraging header appear under Combat.
 *
 * <p><b>There is no GENERAL constant, and there must not be one.</b> A module that declares no
 * subgroup lands in its group's trailing "General" bucket - which is also the right place for a module
 * added later by someone who has never heard of subgroups. "GENERAL" is reserved for that bucket's
 * fold key in {@code CategoryFolding}; a constant with that name would share it.
 * {@code ModuleSubgroupTest} fails if one is added.
 *
 * <p><b>Opt-in per group.</b> A group is drawn with sub-headers only while at least one of its modules
 * declares a subgroup; every other group looks exactly as it did before this existed. Only add one
 * where a group really mixes families - a header over three cards costs a row and buys nothing.
 */
public enum ModuleSubgroup {

    // ---- Skills ----
    FARMING_GARDEN(ModuleGroup.SKILLS, "Farming & Garden"),
    MINING(ModuleGroup.SKILLS, "Mining"),
    FORAGING(ModuleGroup.SKILLS, "Foraging"),
    HUNTING(ModuleGroup.SKILLS, "Hunting"),
    FISHING(ModuleGroup.SKILLS, "Fishing"),

    // ---- Inventory & Items ----
    /** Keeping items where they are, and moving them on purpose: locks, protection, bindings. */
    SLOTS(ModuleGroup.INVENTORY_ITEMS, "Slots & Protection"),
    /** How items read and look: tooltips, overlays, recipes, skins. */
    ITEMS(ModuleGroup.INVENTORY_ITEMS, "Items & Tooltips"),
    /** Reskins and helpers for particular menus, the inventory screen included. */
    MENUS(ModuleGroup.INVENTORY_ITEMS, "Menus"),

    // ---- Quality of Life ----
    /** Getting somewhere, or finding something: maps, warps, souls, NPCs, quests. */
    NAVIGATION(ModuleGroup.QUALITY_OF_LIFE, "Navigation"),
    TIMERS(ModuleGroup.QUALITY_OF_LIFE, "Timers & Reminders");

    /** The sub-header text of the bucket holding a subdivided group's undeclared modules. */
    public static final String GENERAL_NAME = "General";

    private final ModuleGroup group;
    private final String displayName;

    ModuleSubgroup(ModuleGroup group, String displayName) {
        this.group = group;
        this.displayName = displayName;
    }

    /** The group this subgroup lives in. */
    public ModuleGroup group() {
        return group;
    }

    /** Sub-header text. */
    public String displayName() {
        return displayName;
    }
}
