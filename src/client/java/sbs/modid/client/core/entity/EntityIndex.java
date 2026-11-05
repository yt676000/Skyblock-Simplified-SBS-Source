/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.entity;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;

import java.util.List;

/**
 * Every entity in the client level, filed by {@link EntityKind} as it loads - the replacement for
 * per-tick {@code level.entitiesForRendering()} walks (core AGENTS, rule 8).
 *
 * <p>Fed by {@link ClientEntityEvents#ENTITY_LOAD} / {@code ENTITY_UNLOAD}; emptied when the level
 * changes (a new level's first load, or {@link #onClientTick} noticing it) and on every
 * {@code WorldTransitions} transition (registered in {@code WorldScopedTrackers}). A
 * tracker walks only the bucket it needs - typically the armor stands, a fraction of the level - and
 * rejects in the cheap order: bucket → {@code distanceToSqr} → alive → {@link Tracked#nameClass} →
 * name → equipment. Spatial queries ("the mob under this stand") stay on
 * {@code Level.getEntities(AABB)}, which the level already indexes by section; this is no grid.
 *
 * <p>Client thread only. The buckets are live lists: iterate them by index, never modify them, and
 * do not spawn or remove entities while iterating.
 */
public final class EntityIndex {

    private static final Int2ObjectOpenHashMap<Tracked> BY_ID = new Int2ObjectOpenHashMap<>();
    private static final EntityKind[] KINDS = EntityKind.values();
    @SuppressWarnings("unchecked")
    private static final ObjectArrayList<Tracked>[] BUCKETS = new ObjectArrayList[KINDS.length];

    static {
        for (int i = 0; i < BUCKETS.length; i++) {
            BUCKETS[i] = new ObjectArrayList<>();
        }
    }

    // Per bucket, bumped on every add and remove - a join and a leave in one tick still count.
    private static final int[] REVISIONS = new int[KINDS.length];

    private static ClientLevel level;
    private static long reclassifications;

    private EntityIndex() {
    }

    /** Hooks the Fabric entity events; once, from the client initializer. */
    public static void register() {
        ClientEntityEvents.ENTITY_LOAD.register(EntityIndex::onLoad);
        ClientEntityEvents.ENTITY_UNLOAD.register(EntityIndex::onUnload);
    }

    static EntityKind kindOf(Entity entity) {
        if (entity instanceof ArmorStand) {
            return EntityKind.ARMOR_STAND;
        }
        if (entity instanceof Player) {
            return EntityKind.PLAYER;
        }
        if (entity instanceof LivingEntity) {
            return EntityKind.LIVING;
        }
        if (entity instanceof ItemEntity) {
            return EntityKind.ITEM;
        }
        return EntityKind.OTHER;
    }

    private static void onLoad(Entity entity, ClientLevel from) {
        if (from != level) {
            clear();
            level = from;
        }
        Tracked old = BY_ID.get(entity.getId());
        if (old != null) {
            if (old.entity == entity) {
                return;
            }
            remove(old);
        }
        Tracked tracked = new Tracked(entity, kindOf(entity));
        ObjectArrayList<Tracked> bucket = BUCKETS[tracked.kind.ordinal()];
        tracked.slot = bucket.size();
        bucket.add(tracked);
        REVISIONS[tracked.kind.ordinal()]++;
        BY_ID.put(entity.getId(), tracked);
    }

    private static void onUnload(Entity entity, ClientLevel from) {
        Tracked tracked = BY_ID.get(entity.getId());
        if (tracked != null && tracked.entity == entity) {
            remove(tracked);
        }
    }

    private static void remove(Tracked tracked) {
        BY_ID.remove(tracked.entity.getId());
        ObjectArrayList<Tracked> bucket = BUCKETS[tracked.kind.ordinal()];
        int slot = tracked.slot;
        int last = bucket.size() - 1;
        if (slot >= 0 && slot <= last && bucket.get(slot) == tracked) {
            Tracked moved = bucket.get(last);
            bucket.set(slot, moved);
            moved.slot = slot;
            bucket.remove(last);
            REVISIONS[tracked.kind.ordinal()]++;
        }
        tracked.slot = -1;
    }

    /** Start of the client tick: drops everything once the level it was filled from is gone. */
    public static void onClientTick() {
        if (level != null && Minecraft.getInstance().level != level) {
            clear();
        }
    }

    /** World transition / level change: nothing entity-keyed survives it. */
    // BOOKMARK(merge 2026-10-05): hide-other-pets revision bump merged into the world-transitions clear().
    public static void clear() {
        BY_ID.clear();
        for (int i = 0; i < BUCKETS.length; i++) {
            BUCKETS[i].clear();
            REVISIONS[i]++;
        }
        level = null;
    }

    /** The live list of one kind. Iterate by index; do not modify. */
    public static List<Tracked> bucket(EntityKind kind) {
        return BUCKETS[kind.ordinal()];
    }

    /**
     * Changes whenever an entity of {@code kind} loads or unloads, or the index is cleared. Compare
     * for equality only.
     */
    public static int revision(EntityKind kind) {
        return REVISIONS[kind.ordinal()];
    }

    public static int size(EntityKind kind) {
        return BUCKETS[kind.ordinal()].size();
    }

    /** The tracked entry of an entity id, or null. */
    public static Tracked get(int id) {
        return BY_ID.get(id);
    }

    /** The tracked entry of {@code entity}, or null when it is not (or no longer) in the level. */
    public static Tracked get(Entity entity) {
        Tracked tracked = BY_ID.get(entity.getId());
        return tracked != null && tracked.entity == entity ? tracked : null;
    }

    static void countReclassification() {
        reclassifications++;
    }

    /** Name reclassifications since start, for the Perf report's rate. */
    public static long reclassifications() {
        return reclassifications;
    }
}
