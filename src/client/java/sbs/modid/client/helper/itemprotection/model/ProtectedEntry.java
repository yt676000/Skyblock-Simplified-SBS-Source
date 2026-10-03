/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.itemprotection.model;

/**
 * One row of the protected-items file: enough to list an item the player is <b>not</b> currently
 * holding, which is the whole reason the store keeps more than a set of keys.
 *
 * <p>Mutable with a no-arg constructor because Gson builds it. The display name is refreshed
 * whenever the item is seen again, so a renamed or reforged item does not stay listed under a name
 * it no longer has - but the name is never an identity, only a label.
 */
public final class ProtectedEntry {

    /** SkyBlock {@code ExtraAttributes.id}. Empty when the item carried a uuid but no readable id. */
    private String id = "";

    /** Last seen display name, formatting stripped. Display only. */
    private String name = "";

    /** When protection was first granted, epoch millis. Not refreshed on re-sighting. */
    private long addedAt;

    /** Gson. */
    public ProtectedEntry() {
    }

    public ProtectedEntry(String id, String name, long addedAt) {
        this.id = id == null ? "" : id;
        this.name = name == null ? "" : name;
        this.addedAt = addedAt;
    }

    public String id() {
        return id == null ? "" : id;
    }

    public String name() {
        return name == null ? "" : name;
    }

    public long addedAt() {
        return addedAt;
    }

    /** Updates the remembered id / display name; returns whether anything actually changed. */
    public boolean refresh(String newId, String newName) {
        boolean changed = false;
        if (newId != null && !newId.isEmpty() && !newId.equals(this.id)) {
            this.id = newId;
            changed = true;
        }
        if (newName != null && !newName.isEmpty() && !newName.equals(this.name)) {
            this.name = newName;
            changed = true;
        }
        return changed;
    }

    /** What the list screen and {@code /sbs protect list} show: the name, falling back to the id. */
    public String label() {
        if (!name().isEmpty()) {
            return name();
        }
        return id().isEmpty() ? "(unnamed item)" : id();
    }
}
