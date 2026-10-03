/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.hunting.model;

import sbs.modid.client.skills.hunting.logic.ShardCatalog;

/**
 * One shard as the bundled catalogue describes it - the entry {@link ShardCatalog} is built from.
 *
 * <p>A plain class with public fields rather than a record, because Gson builds it field by field
 * out of {@code shards.json} and a record would need a bound adapter for no gain. Missing fields
 * come back {@code null} and are answered as empty by the accessors, which is the normal state:
 * see the class comment on {@link ShardData} for which fields the generator can fill today and
 * which are waiting on a source.
 */
public final class ShardDefinition {

    /** The id-alphabet name ({@code ABYSSAL_LANTERN}). The identity of the shard. */
    public String name = "";

    /** What the game calls it ({@code Abyssal Lantern}) - what the player sees and searches for. */
    public String displayName = "";

    /** {@link ShardRarity#name()}, or absent when no source has stated it yet. */
    public String rarity = "";

    /** {@code ATTRIBUTE_SHARD_<NAME>;1} - carried explicitly so the file is readable on its own. */
    public String canonicalId = "";

    /** The ability the shard grants, when known. Carried for display; nothing keys on it. */
    public String ability = "";

    /** The fusion family the shard belongs to, when known. */
    public String family = "";

    /** The shard's alignment, when known. */
    public String alignment = "";

    /** Hypixel's own short shard id as the Attribute Menu writes it ({@code C12}), when known. */
    public String shardId = "";

    public ShardDefinition() {
    }

    /** An entry is usable when it has a name; everything else is optional. */
    public boolean valid() {
        return name != null && !name.isBlank();
    }

    /** The identity key, {@code ATTRIBUTE_SHARD_<NAME>} with no tier. Never {@code null}. */
    public String key() {
        String stated = canonicalId == null || canonicalId.isBlank() ? null : ShardId.key(canonicalId);
        return stated != null ? stated : ShardId.key(ShardId.of(name));
    }

    /** The canonical id at tier 1, derived when the file did not spell it out. */
    public String canonical() {
        return canonicalId == null || canonicalId.isBlank() ? ShardId.of(name) : canonicalId;
    }

    /** The display name, falling back to the name when the file carries none. */
    public String display() {
        return displayName == null || displayName.isBlank() ? name : displayName;
    }

    /** The rarity, {@link ShardRarity#UNKNOWN} when the file does not state one. */
    public ShardRarity rarity() {
        return ShardRarity.byName(rarity);
    }

    public String ability() {
        return ability == null ? "" : ability;
    }

    public String family() {
        return family == null ? "" : family;
    }

    public String alignment() {
        return alignment == null ? "" : alignment;
    }

    /** Hypixel's short shard id ({@code C12}), or empty when the file does not carry one. */
    public String shortId() {
        return shardId == null ? "" : shardId.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
