/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.foraging.logic;

import com.google.gson.reflect.TypeToken;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.SBSFiles;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Everything the player changed about a shipped preset - and nothing else.
 *
 * <p><b>Deltas, never a copy.</b> The preset itself stays read-only in the jar and the cache; this
 * file holds only what differs from it, keyed by the ids the preset assigns. That is what makes the
 * two update semantics work at once: a corrected preset takes effect immediately because there is no
 * stale copy to shadow it, and a player's correction survives that update because it is keyed to the
 * point rather than being a rewritten copy of it.
 *
 * <p><b>Deleting a point is a tombstone, not a removal.</b> Removing the record would mean "no
 * override", which reads as "show the preset point" - so a deleted tree would come back on the next
 * launch. {@link PointOverride#deleted} is therefore an explicit flag.
 *
 * <p><b>Global, not profile-scoped.</b> A corrected coordinate is a fact about the world, not about
 * one SkyBlock profile. (Per-point <i>timers</i>, when they arrive, are the opposite and belong in a
 * {@code ProfileScopedStore}.)
 *
 * <p>Reset to defaults is deleting the record - which is honest, because the preset was never
 * touched, so the restore is guaranteed to be exactly what ships.
 */
public final class WaypointPresetOverrides {

    private static final WaypointPresetOverrides INSTANCE = new WaypointPresetOverrides();

    private static final Type MAP_TYPE =
            new TypeToken<LinkedHashMap<String, GroupOverride>>() { }.getType();

    /** What the player changed about a whole group. */
    public static final class GroupOverride {
        /** {@code null} = the group's own default; otherwise the player's explicit choice. */
        public Boolean enabled;
        /** {@code RRGGBB}, or empty/absent to keep the group's shipped colour. */
        public String colorHex;
        /**
         * How far the group's markers are drawn, in blocks; {@code 0} is an explicit "no limit" and
         * {@code null} keeps the group's shipped range. Both states are needed: a player who turns
         * the cap off has said something, and saying it must survive a shipped default that changes.
         */
        public Integer maxDistance;
        public Map<String, PointOverride> points = new LinkedHashMap<>();

        /** Gson needs a no-arg constructor. */
        public GroupOverride() {
        }

        /** Whether this record still says anything - an empty one is deleted rather than written. */
        boolean meaningful() {
            return enabled != null
                    || (colorHex != null && !colorHex.isBlank())
                    || maxDistance != null
                    || (points != null && !points.isEmpty());
        }
    }

    /** What the player changed about one point. */
    public static final class PointOverride {
        public Boolean enabled;
        /** A corrected coordinate. All three are set together or none is. */
        public Integer x;
        public Integer y;
        public Integer z;
        public String name;
        public String colorHex;
        /** A tombstone: the point is gone and must stay gone across restarts. */
        public boolean deleted;

        /** Gson needs a no-arg constructor. */
        public PointOverride() {
        }

        boolean meaningful() {
            return deleted || enabled != null || x != null || y != null || z != null
                    || (name != null && !name.isBlank())
                    || (colorHex != null && !colorHex.isBlank());
        }
    }

    private Map<String, GroupOverride> groups;

    /** Bumped on every change so the publisher knows to rebuild its cached list. */
    private volatile int generation;

    private WaypointPresetOverrides() {
    }

    public static WaypointPresetOverrides getInstance() {
        return INSTANCE;
    }

    private static Path file() {
        return SBSFiles.root().resolve("data").resolve("waypoint_overrides.json");
    }

    private synchronized Map<String, GroupOverride> groups() {
        if (groups == null) {
            groups = load();
        }
        return groups;
    }

    private static Map<String, GroupOverride> load() {
        try {
            Path path = file();
            if (Files.isRegularFile(path)) {
                String json = Files.readString(path, StandardCharsets.UTF_8);
                Map<String, GroupOverride> parsed = SBSFiles.GSON.fromJson(json, MAP_TYPE);
                if (parsed != null) {
                    for (GroupOverride group : parsed.values()) {
                        if (group != null && group.points == null) {
                            group.points = new LinkedHashMap<>();
                        }
                    }
                    return parsed;
                }
            }
        } catch (Exception e) {
            // A corrupt override file must not wipe the player's corrections silently: it stays on
            // disk, and we start empty in memory so the preset is still fully usable.
            SkyblockSimplifiedSBS.LOGGER.warn(
                    "[SBS][Presets] could not read waypoint_overrides.json ({}) - ignoring it",
                    e.toString());
        }
        return new LinkedHashMap<>();
    }

    private synchronized void save() {
        generation++;
        // An empty record says nothing; keeping it would make "reset" leave litter behind that a
        // later reader cannot tell from a deliberate choice.
        groups().entrySet().removeIf(entry -> entry.getValue() == null || !entry.getValue().meaningful());
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, SBSFiles.GSON.toJson(groups(), MAP_TYPE), StandardCharsets.UTF_8);
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Presets] could not write waypoint_overrides.json",
                    e);
        }
    }

    /** Cache key for anything derived from these records. */
    public int generation() {
        return generation;
    }

    // ------------------------------------------------------------------ reads

    /** The record for a group, or {@code null} when the player has not changed anything about it. */
    public synchronized GroupOverride group(String groupId) {
        return groupId == null ? null : groups().get(groupId);
    }

    /** The record for a point, or {@code null}. */
    public synchronized PointOverride point(String groupId, String pointId) {
        GroupOverride group = group(groupId);
        if (group == null || group.points == null || pointId == null) {
            return null;
        }
        return group.points.get(pointId);
    }

    /** Whether a group draws, honouring the player's choice over the group's shipped default. */
    public synchronized boolean groupEnabled(String groupId, boolean shippedDefault) {
        GroupOverride group = group(groupId);
        return group == null || group.enabled == null ? shippedDefault : group.enabled;
    }

    /** How far a group draws, honouring the player's choice over the group's shipped range. */
    public synchronized int groupMaxDistance(String groupId, int shippedDefault) {
        GroupOverride group = group(groupId);
        return group == null || group.maxDistance == null ? shippedDefault : group.maxDistance;
    }

    // ------------------------------------------------------------------ writes

    private synchronized GroupOverride mutableGroup(String groupId) {
        return groups().computeIfAbsent(groupId, key -> new GroupOverride());
    }

    public synchronized void setGroupEnabled(String groupId, boolean enabled, boolean shippedDefault) {
        if (groupId == null) {
            return;
        }
        GroupOverride group = mutableGroup(groupId);
        // Choosing the shipped default again clears the record rather than pinning it: the player is
        // back to "whatever ships", which must keep following the data if the default ever changes.
        group.enabled = enabled == shippedDefault ? null : enabled;
        save();
    }

    public synchronized void setGroupMaxDistance(String groupId, int blocks, int shippedDefault) {
        if (groupId == null) {
            return;
        }
        GroupOverride group = mutableGroup(groupId);
        // Same rule as the toggle: sliding back to the shipped range clears the record instead of
        // pinning it, so the player keeps following the data if the shipped range is ever corrected.
        group.maxDistance = blocks == shippedDefault ? null : Math.max(0, blocks);
        save();
    }

    public synchronized void setGroupColor(String groupId, String colorHex) {
        if (groupId == null) {
            return;
        }
        mutableGroup(groupId).colorHex = colorHex == null || colorHex.isBlank() ? null : colorHex.trim();
        save();
    }

    private synchronized PointOverride mutablePoint(String groupId, String pointId) {
        GroupOverride group = mutableGroup(groupId);
        if (group.points == null) {
            group.points = new LinkedHashMap<>();
        }
        return group.points.computeIfAbsent(pointId, key -> new PointOverride());
    }

    public synchronized void setPointEnabled(String groupId, String pointId, boolean enabled) {
        if (groupId == null || pointId == null) {
            return;
        }
        PointOverride point = mutablePoint(groupId, pointId);
        point.enabled = enabled ? null : Boolean.FALSE;   // enabled is the shipped state
        prune(groupId, pointId, point);
    }

    /** Corrects a point's coordinates - the edit that must survive a preset update. */
    public synchronized void movePoint(String groupId, String pointId, int x, int y, int z) {
        if (groupId == null || pointId == null) {
            return;
        }
        PointOverride point = mutablePoint(groupId, pointId);
        point.x = x;
        point.y = y;
        point.z = z;
        save();
    }

    /** Deletes a point. A tombstone, so it does not come back on the next launch. */
    public synchronized void deletePoint(String groupId, String pointId) {
        if (groupId == null || pointId == null) {
            return;
        }
        mutablePoint(groupId, pointId).deleted = true;
        save();
    }

    /** Drops a record that no longer says anything, then persists. */
    private synchronized void prune(String groupId, String pointId, PointOverride point) {
        if (!point.meaningful()) {
            GroupOverride group = group(groupId);
            if (group != null && group.points != null) {
                group.points.remove(pointId);
            }
        }
        save();
    }

    /** Reset to defaults for one group: forget everything the player said about it. */
    public synchronized void reset(String groupId) {
        if (groupId != null && groups().remove(groupId) != null) {
            save();
        }
    }

    /** Reset every group. */
    public synchronized void resetAll() {
        if (!groups().isEmpty()) {
            groups().clear();
            save();
        }
    }

    /** How many groups the player has changed anything about, for the settings status line. */
    public synchronized int changedGroups() {
        return groups().size();
    }
}
