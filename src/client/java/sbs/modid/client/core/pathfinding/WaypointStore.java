/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.pathfinding;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The pathfinding module's waypoints: adding, removing and finding the one to route to.
 *
 * <p>Waypoints live in the config, so they survive restarts and can be edited by hand. This class is
 * the only writer, which keeps "add a waypoint" a single call from a keybind, a command or a future
 * data source (a fairy-soul list, a route file, ...) – the pathfinder itself never knows where a
 * waypoint came from.
 */
public final class WaypointStore {

    private WaypointStore() {
    }

    private static SBSConfig.PathfindingSettings cfg() {
        return ConfigManager.getInstance().get().pathfinding;
    }

    /**
     * Waypoints a feature owns for the moment and nobody should keep - dozens of Fairy Souls, a
     * scan's worth of markers.
     *
     * <p>Kept out of the config entirely rather than persisted and cleaned up later. A feature that
     * publishes twenty-odd markers per island would otherwise grow {@code config.json} without bound
     * and write it on every island change, and none of it is worth surviving a restart: it is
     * derived from data the mod reloads anyway.
     */
    private static final List<Waypoint> TRANSIENT = new ArrayList<>();

    /** All persisted waypoints, including ones from other dimensions. */
    public static List<Waypoint> all() {
        List<Waypoint> list = cfg().waypoints;
        return list == null ? List.of() : list;
    }

    /**
     * Replaces every transient waypoint owned by {@code source}. Nothing is written to disk.
     *
     * <p>Wholesale replacement rather than incremental edits because the caller recomputes its whole
     * set anyway (souls collected, island changed), and a set swap cannot leave a stale marker behind.
     */
    public static synchronized void setTransient(String source, List<Waypoint> waypoints) {
        if (source == null) {
            return;
        }
        boolean removed = TRANSIENT.removeIf(waypoint -> source.equals(waypoint.source));
        boolean added = waypoints != null && !waypoints.isEmpty();
        if (added) {
            TRANSIENT.addAll(waypoints);
        }
        if (removed || added) {
            rebuild();
            PathfindingManager.getInstance().invalidate();
        }
    }

    /** Drops every transient waypoint owned by {@code source}. */
    public static void clearTransient(String source) {
        setTransient(source, List.of());
    }

    /**
     * An immutable view of the store, swapped whole on every change so the per-frame questions -
     * {@link #hasAny}, {@link #hasSource}, {@link #inCurrentDimension} - are a volatile read rather
     * than a synchronized scan. The renderer used to ask {@code hasSource} a dozen times a frame,
     * each a linear walk of every transient marker under the store's lock.
     *
     * <p>{@code persisted} is the config list's contents at build time. Features still edit that
     * list in place through {@link #all()}, so {@link #refresh()} compares it element by element
     * once per tick; a remove-then-add of the same size would slip past a size check alone.
     */
    private record Snapshot(List<Waypoint> persistedList, Waypoint[] persisted, String dimension,
                            Map<String, Integer> sources, List<Waypoint> inDimension, boolean any) {
    }

    private static final Snapshot EMPTY = new Snapshot(null, new Waypoint[0], null, Map.of(), List.of(), false);

    private static volatile Snapshot snapshot = EMPTY;

    /**
     * The current snapshot, rebuilt first when the dimension, the config list's identity or its size
     * moved - all three are cheap to see. In-place edits of equal size are caught by {@link #refresh}.
     */
    private static Snapshot snapshot() {
        Snapshot current = snapshot;
        List<Waypoint> persisted = cfg().waypoints;
        if (current.persistedList() != persisted
                || current.persisted().length != (persisted == null ? 0 : persisted.size())
                || !currentDimension().equals(current.dimension())) {
            return rebuild();
        }
        return current;
    }

    /** Called once per client tick: catches a same-size in-place edit of the config list. */
    public static void refresh() {
        Snapshot current = snapshot();
        List<Waypoint> persisted = cfg().waypoints;
        if (persisted == null) {
            return;
        }
        Waypoint[] seen = current.persisted();
        for (int i = 0; i < seen.length; i++) {
            if (seen[i] != persisted.get(i)) {
                rebuild();
                return;
            }
        }
    }

    private static synchronized Snapshot rebuild() {
        List<Waypoint> persistedList = cfg().waypoints;
        List<Waypoint> persisted = persistedList == null ? List.of() : persistedList;
        String dimension = currentDimension();
        Map<String, Integer> sources = new HashMap<>();
        List<Waypoint> inDimension = new ArrayList<>();
        for (Waypoint waypoint : persisted) {
            count(sources, waypoint);
            if (waypoint.inDimension(dimension)) {
                inDimension.add(waypoint);
            }
        }
        for (Waypoint waypoint : TRANSIENT) {
            count(sources, waypoint);
            if (waypoint.inDimension(dimension)) {
                inDimension.add(waypoint);
            }
        }
        Snapshot built = new Snapshot(persistedList, persisted.toArray(new Waypoint[0]), dimension,
                Map.copyOf(sources), List.copyOf(inDimension), !persisted.isEmpty() || !TRANSIENT.isEmpty());
        snapshot = built;
        return built;
    }

    private static void count(Map<String, Integer> sources, Waypoint waypoint) {
        if (waypoint.source != null) {
            sources.merge(waypoint.source, 1, Integer::sum);
        }
    }

    /** Whether any waypoint exists at all - the renderer's cheapest possible early-out. */
    public static boolean hasAny() {
        return snapshot().any();
    }

    /**
     * Whether anything is currently published under {@code source}.
     *
     * <p>Exists so a renderer gate can ask "does this publisher have something to show" without
     * reaching into the publisher itself - {@code core} must not depend on a feature package. For a
     * publisher whose output already encodes its own conditions, this is the whole gate: if a
     * waypoint is there, it was asked for.
     */
    public static boolean hasSource(String source) {
        return source != null && snapshot().sources().containsKey(source);
    }

    /**
     * The waypoints in the player's current dimension – the only ones worth drawing or routing to.
     * Includes the transient ones, which is the whole point of them being visible to the router.
     * Immutable: it is the snapshot's own list, shared by every caller this tick.
     */
    public static List<Waypoint> inCurrentDimension() {
        return snapshot().inDimension();
    }

    /** The dimension key the player is in, or an empty string when not in a world. */
    public static String currentDimension() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return "";
        }
        // Resource keys are interned, so the key's identity says whether the string is still right;
        // it is built once per dimension change instead of on every snapshot() call.
        Object key = minecraft.level.dimension();
        DimensionName last = lastDimension;
        if (last.key() != key) {
            last = new DimensionName(key, minecraft.level.dimension().identifier().toString());
            lastDimension = last;
        }
        return last.name();
    }

    private record DimensionName(Object key, String name) {
    }

    private static volatile DimensionName lastDimension = new DimensionName(null, "");

    /** Adds a waypoint at the player's feet and persists it. Returns the new waypoint. */
    public static Waypoint addAtPlayer() {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return null;
        }
        return add(player.blockPosition());
    }

    /** Adds an auto-named waypoint at {@code pos} and persists it. */
    public static Waypoint add(BlockPos pos) {
        SBSConfig.PathfindingSettings cfg = cfg();
        if (cfg.waypoints == null) {
            cfg.waypoints = new ArrayList<>();
        }
        Waypoint waypoint = new Waypoint("Waypoint " + (cfg.waypoints.size() + 1), pos, currentDimension());
        cfg.waypoints.add(waypoint);
        ConfigManager.getInstance().save();
        rebuild();
        PathfindingManager.getInstance().invalidate();
        notifyPlayer("§a[SBS] Added " + waypoint.summary());
        return waypoint;
    }

    /**
     * Adds a waypoint a feature built itself (an objective marker, a map destination) and persists.
     *
     * <p>Exists so a caller never touches {@link #all()} to mutate it: that list is {@link List#of()}
     * when the config has no waypoints array at all, and adding to it would throw. Going through here
     * creates the array first, which is also what {@link #add} has always done.
     */
    public static void publish(Waypoint waypoint) {
        if (waypoint == null) {
            return;
        }
        SBSConfig.PathfindingSettings cfg = cfg();
        if (cfg.waypoints == null) {
            cfg.waypoints = new ArrayList<>();
        }
        cfg.waypoints.add(waypoint);
        ConfigManager.getInstance().save();
        rebuild();
        PathfindingManager.getInstance().invalidate();
    }

    /**
     * Removes every waypoint a given feature placed. Returns whether anything was removed.
     *
     * <p>The counterpart to {@link #publish}, and safe against the same empty-list case.
     */
    public static boolean removeSource(String source) {
        SBSConfig.PathfindingSettings cfg = cfg();
        if (cfg.waypoints == null || cfg.waypoints.isEmpty() || source == null) {
            return false;
        }
        if (!cfg.waypoints.removeIf(waypoint -> source.equals(waypoint.source))) {
            return false;
        }
        ConfigManager.getInstance().save();
        rebuild();
        PathfindingManager.getInstance().invalidate();
        return true;
    }

    /** Removes every waypoint and persists. */
    public static void clear() {
        SBSConfig.PathfindingSettings cfg = cfg();
        if (cfg.waypoints != null) {
            cfg.waypoints.clear();
        }
        ConfigManager.getInstance().save();
        rebuild();
        PathfindingManager.getInstance().invalidate();
        notifyPlayer("§7[SBS] Cleared all waypoints");
    }

    /** Removes the waypoint nearest to the player (the natural "undo" while dropping markers). */
    public static void removeNearest() {
        Player player = Minecraft.getInstance().player;
        List<Waypoint> waypoints = cfg().waypoints;
        if (player == null || waypoints == null || waypoints.isEmpty()) {
            return;
        }
        Waypoint nearest = nearestTo(player.blockPosition(), waypoints);
        if (nearest != null) {
            waypoints.remove(nearest);
            ConfigManager.getInstance().save();
            rebuild();
            PathfindingManager.getInstance().invalidate();
            notifyPlayer("§7[SBS] Removed " + nearest.summary());
        }
    }

    /** The waypoint closest to {@code from} out of {@code candidates}, or {@code null} if none. */
    public static Waypoint nearestTo(BlockPos from, List<Waypoint> candidates) {
        Waypoint best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Waypoint waypoint : candidates) {
            double distance = waypoint.pos().distSqr(from);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = waypoint;
            }
        }
        return best;
    }

    private static void notifyPlayer(String message) {
        Player player = Minecraft.getInstance().player;
        if (player != null) {
            player.sendSystemMessage(Component.literal(message));
        }
    }
}
