/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.hunting.model;

/**
 * One shard kind as the Hunting Box showed it: what it is, how many are in there, and how sure we
 * are of the count.
 *
 * <p>A plain class rather than a record so Gson can rebuild it from the profile file field by field.
 * {@code rarityName} is stored as the enum's own name because a stored ordinal silently repoints at
 * a different rarity the day one is inserted.
 */
public final class HuntingBoxShard {

    private String id = "";
    private String name = "";
    private String rarityName = ShardRarity.UNKNOWN.name();
    private int count;

    /**
     * Whether the count came from a lore line ({@code "x1,234"}) rather than the stack size.
     *
     * <p>Kept because the two are not equally trustworthy: a stack size stops at 64 and would
     * under-report a real box, while a lore line is whatever Hypixel wrote and could be a total, a
     * page count or something else entirely. Until a live capture settles which the box uses, the UI
     * can say which one it read.
     */
    private boolean countFromLore;

    public HuntingBoxShard() {
    }

    public HuntingBoxShard(String id, String name, ShardRarity rarity, int count, boolean countFromLore) {
        this.id = id == null ? "" : id;
        this.name = name == null ? "" : name;
        this.rarityName = (rarity == null ? ShardRarity.UNKNOWN : rarity).name();
        this.count = count;
        this.countFromLore = countFromLore;
    }

    public String id() {
        return id == null ? "" : id;
    }

    public String name() {
        return name == null || name.isEmpty() ? id() : name;
    }

    public ShardRarity rarity() {
        return ShardRarity.byName(rarityName);
    }

    public int count() {
        return count;
    }

    public boolean countFromLore() {
        return countFromLore;
    }

    /** Merges another sighting of the same shard into this one (the box may split a kind). */
    public void add(int more) {
        count += Math.max(0, more);
    }
}
