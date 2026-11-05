/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.storage;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Identity and numbering of the island chests the item search remembers.
 *
 * <p><b>Why per island.</b> A chest is only known by its block position, and a position means
 * something only on an island whose layout is the player's own: the Private Island (and, opted in,
 * the Garden). Those are keyed {@code chest:<island>:<x>,<y>,<z>} and persisted per profile, so the
 * same coordinates on two islands are two chests and "Chest 12" is the same chest next session.
 * Everywhere else - a dungeon, a hub, someone else's island - the position is meaningless after the
 * world changes, so those chests stay session-only under the old {@code chest:<x>,<y>,<z>} id and die
 * with the world, exactly as before.
 *
 * <p><b>Double chests.</b> Both halves open the same menu, and the click can land on either. Each is
 * keyed by its {@link #primary primary} half so the two halves are one entry; the other half is
 * kept as {@link Place#partner()} for the marker box.
 *
 * <p>Pure bookkeeping, no Minecraft state: {@link StorageIndex} owns the instance and the threading.
 */
public final class IslandChests {

    public static final String PRIVATE_ISLAND = "private_island";
    public static final String GARDEN = "garden";

    /** Island key for chests that are not remembered across worlds. */
    private static final String SESSION = "";

    /**
     * A remembered chest.
     *
     * @param island  the island key, or {@code null} for a session-only chest
     * @param pos     the primary block (the only one for a single chest)
     * @param partner the other half of a double chest, or {@code null}
     * @param number  the stable "Chest N" number on that island
     */
    public record Place(String island, BlockPos pos, BlockPos partner, int number) {

        public boolean persisted() {
            return island != null;
        }

        public String id() {
            return IslandChests.id(island, pos);
        }

        public String displayName() {
            return "Chest " + number;
        }

        /** Every block the chest occupies - one, or both halves. */
        public List<BlockPos> blocks() {
            return partner == null ? List.of(pos) : List.of(pos, partner);
        }
    }

    /** {@link Place} on disk. Plain ints so the file stays readable and needs no adapter. */
    public record Persisted(String island, int x, int y, int z, Integer px, Integer py, Integer pz, int number) {
    }

    private final Map<String, Map<BlockPos, Integer>> numbers = new HashMap<>();

    /**
     * The island key for a chest opened on {@code island} (as {@code SkyBlockLocation.island()} names
     * it), or {@code null} when chests there are not remembered.
     */
    public static String islandKey(String island, boolean includeGarden) {
        if (island == null) {
            return null;
        }
        String name = island.trim();
        if (name.equalsIgnoreCase("Private Island") || name.equalsIgnoreCase("Your Island")) {
            return PRIVATE_ISLAND;
        }
        if (includeGarden && (name.equalsIgnoreCase("The Garden") || name.equalsIgnoreCase("Garden"))) {
            return GARDEN;
        }
        return null;
    }

    /** The island key's name for the player. */
    public static String islandName(String key) {
        return GARDEN.equals(key) ? "Garden" : "Private Island";
    }

    /** The storage id of a chest. */
    public static String id(String island, BlockPos pos) {
        String coords = pos.getX() + "," + pos.getY() + "," + pos.getZ();
        return island == null ? "chest:" + coords : "chest:" + island + ":" + coords;
    }

    /** The half a double chest is keyed by: lowest y, then x, then z - the same for either click. */
    public static BlockPos primary(BlockPos a, BlockPos b) {
        if (b == null) {
            return a;
        }
        if (a.getY() != b.getY()) {
            return a.getY() < b.getY() ? a : b;
        }
        if (a.getX() != b.getX()) {
            return a.getX() < b.getX() ? a : b;
        }
        return a.getZ() <= b.getZ() ? a : b;
    }

    /**
     * The chest at a clicked block, numbered on its island. {@code otherHalf} is the connected half
     * of a double chest (or {@code null}); either half yields the same place.
     */
    public Place place(String island, BlockPos clicked, BlockPos otherHalf) {
        BlockPos pos = primary(clicked, otherHalf).immutable();
        BlockPos partner = otherHalf == null ? null : (pos.equals(clicked) ? otherHalf : clicked).immutable();
        Map<BlockPos, Integer> onIsland = numbers.computeIfAbsent(island == null ? SESSION : island, k -> new HashMap<>());
        Integer number = onIsland.get(pos);
        if (number == null) {
            number = onIsland.values().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
            onIsland.put(pos, number);
        }
        return new Place(island, pos, partner, number);
    }

    /** Takes over a place read from disk, so its number is reserved before anything new is opened. */
    public void restore(Place place) {
        numbers.computeIfAbsent(place.island() == null ? SESSION : place.island(), k -> new HashMap<>())
                .put(place.pos(), place.number());
    }

    /** Drops the session-only numbering (world change); island numbering is kept. */
    public void forgetSession() {
        numbers.remove(SESSION);
    }

    /** Drops everything (profile switch, manual reset). */
    public void clear() {
        numbers.clear();
    }

    public static Persisted toDisk(Place place) {
        BlockPos p = place.partner();
        return new Persisted(place.island(), place.pos().getX(), place.pos().getY(), place.pos().getZ(),
                p == null ? null : p.getX(), p == null ? null : p.getY(), p == null ? null : p.getZ(),
                place.number());
    }

    /** The place a disk record describes, or {@code null} for a malformed one. */
    public static Place fromDisk(Persisted disk) {
        if (disk == null || disk.island() == null || disk.number() <= 0) {
            return null;
        }
        BlockPos partner = disk.px() == null || disk.py() == null || disk.pz() == null
                ? null : new BlockPos(disk.px(), disk.py(), disk.pz());
        return new Place(disk.island(), new BlockPos(disk.x(), disk.y(), disk.z()), partner, disk.number());
    }

    /** Every place in {@code places} on {@code island}, in number order. */
    public static List<Place> on(String island, Iterable<Place> places) {
        List<Place> out = new ArrayList<>();
        for (Place place : places) {
            if (island != null && island.equals(place.island())) {
                out.add(place);
            }
        }
        out.sort((a, b) -> Integer.compare(a.number(), b.number()));
        return out;
    }
}
