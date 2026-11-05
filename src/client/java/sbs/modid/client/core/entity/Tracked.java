/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.entity;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import sbs.modid.client.core.util.PlainText;

/**
 * One entity in {@link EntityIndex}, with its custom name read and classified once per change.
 *
 * <p><b>Change detection by identity is safe here</b>: Minecraft never mutates a custom-name
 * {@link Component} in place - a metadata packet (or a client-side {@code setCustomName}) replaces
 * the reference. So {@link #refresh} compares references and only reads the text when it changed;
 * an unchanged name costs one field read and one compare, and allocates nothing.
 *
 * <p>Client thread only.
 */
public final class Tracked {

    public final Entity entity;
    public final EntityKind kind;
    /** Position in its bucket's list, for O(1) removal. */
    int slot = -1;

    private Component name;
    private boolean read;
    private String raw = "";
    private String plain = "";
    private NameClass cls = NameClass.NONE;
    private int revision;
    private String mobName;
    private boolean mobNameDone;
    private String petOwner;
    private boolean petOwnerDone;

    /**
     * The Performance module's per-label cache (render-distance class keyed by {@link #revision},
     * throttled occlusion result). Lives and dies with this entry, so it needs no clearing of its own.
     */
    public Object labelState;

    Tracked(Entity entity, EntityKind kind) {
        this.entity = entity;
        this.kind = kind;
    }

    /** Re-reads the custom name if its reference changed; true when it did. */
    public boolean refresh() {
        return update(entity.getCustomName());
    }

    /** The identity check itself, split out so it is unit-tested without an entity. */
    boolean update(Component current) {
        if (read && current == name) {
            return false;
        }
        read = true;
        name = current;
        raw = current == null ? "" : current.getString();
        plain = PlainText.strip(raw);
        cls = NameClass.classify(plain);
        mobName = null;
        mobNameDone = false;
        petOwner = null;
        petOwnerDone = false;
        revision++;
        EntityIndex.countReclassification();
        return true;
    }

    // State as last read, without refreshing - for the tests.
    String cachedPlain() {
        return plain;
    }

    NameClass cachedClass() {
        return cls;
    }

    int cachedRevision() {
        return revision;
    }

    /** The name's {@code getString()}, legacy {@code §} codes included; "" without a name. */
    public String raw() {
        refresh();
        return raw;
    }

    /** The name without {@code §} codes; "" without a name. */
    public String plain() {
        refresh();
        return plain;
    }

    public NameClass nameClass() {
        refresh();
        return cls;
    }

    /** The custom name component as last read (null without one). */
    public Component name() {
        refresh();
        return name;
    }

    /** Bumped on every name change - a key for a consumer's own per-name cache. */
    public int revision() {
        refresh();
        return revision;
    }

    /** {@link NametagText#mobName} of {@link #raw}, computed at most once per name. */
    public String mobName() {
        refresh();
        if (!mobNameDone) {
            mobName = raw.isEmpty() ? null : NametagText.mobName(raw);
            mobNameDone = true;
        }
        return mobName;
    }

    /**
     * The lower-cased {@link NameClass#petOwner} of a {@link NameClass#PET} name, computed at most
     * once per name; null for any other name.
     */
    public String petOwner() {
        refresh();
        if (!petOwnerDone) {
            String owner = cls == NameClass.PET ? NameClass.petOwner(plain) : null;
            petOwner = owner == null ? null : owner.toLowerCase(java.util.Locale.ROOT);
            petOwnerDone = true;
        }
        return petOwner;
    }
}
