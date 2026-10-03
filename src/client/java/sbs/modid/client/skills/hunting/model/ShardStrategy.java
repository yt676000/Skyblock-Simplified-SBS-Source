/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.hunting.model;

/**
 * Which reader produced a shard id, recorded beside every resolution.
 *
 * <p>Kept rather than discarded because "no shard was found" and "the wrong reader ran" look
 * identical from outside, and the second is what shipped for a whole release: the Attribute Menu was
 * being read by display name, which there names an attribute rather than a shard, so almost nothing
 * matched and nothing said why. A resolution that names its own strategy makes that visible in one
 * line of {@code /sbs sharddump} and in the log, without a second trip in game.
 */
public enum ShardStrategy {

    /**
     * The Attribute Menu's {@code "Source: Toxic Shard (C12)"} lore line - the only per-shard
     * identity that menu carries, since its display name is the attribute's.
     */
    SOURCE_LORE("Source: lore line"),

    /** The colour-stripped display name. Correct in the Hunting Box and the fusion menus only. */
    DISPLAY_NAME("display name"),

    /** The first lore line, which is where the Confirm Fusion dialog states the shard. */
    FIRST_LORE_LINE("first lore line"),

    /** {@code ExtraAttributes} on a real item: the shared id plus its {@code attributes} compound. */
    ITEM_NBT("item NBT"),

    /** Nothing answered - a filler pane, a control, or an entry no reader here understands. */
    NONE("nothing");

    private final String displayName;

    ShardStrategy(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** Whether this strategy actually produced an id. */
    public boolean resolved() {
        return this != NONE;
    }
}
