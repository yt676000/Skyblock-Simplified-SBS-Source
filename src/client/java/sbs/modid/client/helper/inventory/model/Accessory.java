/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.inventory.model;

/**
 * One accessory in the catalogue, as {@code scripts/accessories_import.py} writes it out of
 * Hypixel's own item resource.
 *
 * <p>Everything here is a <i>fact about the game</i>, never about a player: what exists, what it is
 * called, how rare it is and which upgrade ladder it sits on. What a given player owns is
 * {@link sbs.modid.client.helper.inventory.logic.AccessoryIndex}'s business, and keeping the two
 * apart is what lets the catalogue be replaced wholesale without touching anyone's progress.
 */
public final class Accessory {

    /** Hypixel's SkyBlock item id ({@code WOLF_RING}). The identity everything keys on. */
    public String id = "";

    /** Display name, colour codes already stripped by the generator. */
    public String name = "";

    /**
     * Hypixel's rarity ({@code LEGENDARY}), or blank when the resource carries none - 38 accessories
     * genuinely have no tier, and inventing one for them would invent a Magical Power number too.
     */
    public String tier = "";

    /**
     * The upgrade ladder this sits on, or blank when it stands alone. Two accessories with the same
     * family are tiers of one another, so owning the higher one means the lower is not missing.
     */
    public String family = "";

    /** Position on {@link #family}, ascending. Meaningless without a family. */
    public int step;

    /** {@code RIFT} for accessories that only exist inside the Rift, else blank. */
    public String origin = "";

    /** {@code SOLO} or {@code COOP} when the accessory is soulbound, else blank. */
    public String soulbound = "";

    /** Whether Hypixel marks it as museum-donatable. */
    public boolean museum;

    public Accessory() {
    }

    /** An entry with no id cannot be matched against anything and is dropped at load. */
    public boolean valid() {
        return id != null && !id.isBlank();
    }

    public boolean hasFamily() {
        return family != null && !family.isBlank();
    }

    /** Rift accessories are a separate world's progression and are filtered apart from the rest. */
    public boolean rift() {
        return "RIFT".equals(origin);
    }

    public boolean soulbound() {
        return soulbound != null && !soulbound.isBlank();
    }

    /** The name to show, falling back to the id so a nameless entry is still identifiable. */
    public String displayName() {
        return name == null || name.isBlank() ? id : name;
    }
}
