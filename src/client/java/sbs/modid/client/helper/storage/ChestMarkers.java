/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.storage;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.pathfinding.MarkerBox;
import sbs.modid.client.core.pathfinding.Waypoint;
import sbs.modid.client.core.pathfinding.WaypointStore;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Marks the island chests that hold the item clicked in the search.
 *
 * <p>The marks are transient waypoints under one source, {@link #SOURCE}: a box on every block of
 * the chest (both halves of a double) and a label "Chest 12 · 34×" on the primary half. They are
 * only published while the player is on the chests' island; clicked from anywhere else they wait,
 * and appear on arrival.
 *
 * <p>They go away when the chest is opened (found), when the player leaves the island after seeing
 * them, after {@link SBSConfig.StorageSearchSettings#chestMarkerMinutes} minutes, or on demand
 * ({@link #clear}, the search screen's button and the clear key).
 *
 * <p>Client thread only: set from the search screen, ticked from {@link StorageIndex#tick}.
 */
public final class ChestMarkers {

    /** {@link Waypoint#source} of every chest mark - its own tag, since setTransient swaps a whole set. */
    public static final String SOURCE = "storage_chests";

    private static final String MIDDLE_DOT = String.valueOf((char) 0x00B7);
    private static final String TIMES = String.valueOf((char) 0x00D7);

    /** One chest to mark and how many of the item are in it. */
    public record Mark(IslandChests.Place place, int count) {

        public String label() {
            return place.displayName() + " " + MIDDLE_DOT + " " + count + TIMES;
        }
    }

    /** The search click, split into what can be marked and what can only be listed. */
    public record Plan(List<Mark> marks, List<StorageIndex.Located> others) {
    }

    private static List<Mark> marks = List.of();
    private static long markedAt;
    private static boolean published;

    private ChestMarkers() {
    }

    private static SBSConfig.StorageSearchSettings cfg() {
        return ConfigManager.getInstance().get().storageSearch;
    }

    /**
     * Splits an entry's locations into remembered island chests ({@code lookup} resolves a storage id
     * to its place, {@code null} when it is not one) and everything else.
     */
    public static Plan plan(List<StorageIndex.Located> locations, Function<String, IslandChests.Place> lookup) {
        List<Mark> marks = new ArrayList<>();
        List<StorageIndex.Located> others = new ArrayList<>();
        for (StorageIndex.Located located : locations) {
            IslandChests.Place place = located.source().kind() == StorageSource.Kind.CHEST
                    ? lookup.apply(located.source().id()) : null;
            if (place != null && place.persisted()) {
                marks.add(new Mark(place, located.count()));
            } else {
                others.add(located);
            }
        }
        return new Plan(marks, others);
    }

    /** The waypoints for a set of marks: one per chest block, the label on the primary half only. */
    public static List<Waypoint> waypoints(List<Mark> marks, String dimension, boolean path) {
        List<Waypoint> out = new ArrayList<>();
        for (Mark mark : marks) {
            for (BlockPos block : mark.place().blocks()) {
                boolean primary = block.equals(mark.place().pos());
                Waypoint waypoint = new Waypoint(primary ? mark.label() : "", block, dimension, SOURCE);
                waypoint.box = MarkerBox.OUTLINE;
                waypoint.beam = primary;
                waypoint.showDistance = primary;
                // The route line is the "tracer": only the labelled half is a destination.
                waypoint.routable = path && primary;
                out.add(waypoint);
            }
        }
        return out;
    }

    /** Whether any chest is marked or waiting to be. */
    public static boolean active() {
        return !marks.isEmpty();
    }

    /**
     * Marks these chests (replacing any earlier set) and tells the player where they are. Returns
     * whether they are on that island now, i.e. whether the marks are already visible.
     */
    public static boolean mark(Plan plan) {
        marks = List.copyOf(plan.marks());
        markedAt = System.currentTimeMillis();
        published = false;
        String island = marks.isEmpty() ? null : marks.get(0).place().island();
        boolean here = island != null && island.equals(StorageIndex.getInstance().currentIslandKey());
        List<String> names = new ArrayList<>();
        for (Mark mark : marks) {
            names.add(mark.label());
        }
        StringBuilder message = new StringBuilder("§b[SBS] §7");
        if (here) {
            message.append("Marked ").append(join(names)).append('.');
        } else {
            message.append("In ").append(join(names)).append(" on your ")
                    .append(IslandChests.islandName(island)).append(" - marked when you get there.");
        }
        if (!plan.others().isEmpty()) {
            List<String> others = new ArrayList<>();
            for (StorageIndex.Located other : plan.others()) {
                others.add(other.source().displayName() + " (" + other.count() + TIMES + ")");
            }
            message.append(" §8Also in: §7").append(join(others)).append('.');
        }
        notifyPlayer(message.toString());
        tick();
        return here;
    }

    /** Removes every mark. */
    public static void clear() {
        marks = List.of();
        if (published) {
            WaypointStore.clearTransient(SOURCE);
        }
        published = false;
    }

    /** A chest was opened: if it was marked, it has been found. */
    public static void onChestOpened(String id) {
        if (marks.isEmpty()) {
            return;
        }
        List<Mark> rest = new ArrayList<>(marks);
        if (!rest.removeIf(mark -> mark.place().id().equals(id))) {
            return;
        }
        marks = List.copyOf(rest);
        if (marks.isEmpty()) {
            clear();
        } else if (published) {
            publish();
        }
    }

    /** Client tick: publish on arrival, expire, and clear on leaving the island. */
    public static void tick() {
        if (marks.isEmpty()) {
            return;
        }
        if (!cfg().markChests) {
            clear();
            return;
        }
        long minutes = Math.max(1, cfg().chestMarkerMinutes);
        if (System.currentTimeMillis() - markedAt > minutes * 60_000L) {
            clear();
            return;
        }
        boolean here = marks.get(0).place().island().equals(StorageIndex.getInstance().currentIslandKey());
        if (here && !published) {
            publish();
        } else if (!here && published) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Storage] left the island, chest markers cleared");
            clear();
        }
    }

    private static void publish() {
        WaypointStore.setTransient(SOURCE, waypoints(marks, WaypointStore.currentDimension(), cfg().chestMarkerPath));
        published = true;
    }

    private static String join(List<String> parts) {
        if (parts.size() <= 1) {
            return parts.isEmpty() ? "" : parts.get(0);
        }
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }

    private static void notifyPlayer(String message) {
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.sendSystemMessage(Component.literal(message));
        }
    }
}
