/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.hunting.hideyho.logic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.BlockPos;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.SBSFiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The hiding places this client has <b>found the Hideyho in itself</b> - the catalogue that fills
 * itself, and the only source of spots the finder has.
 *
 * <p><b>Nothing is shipped, and that is a licensing decision as much as a data one.</b> Two lists of
 * these coordinates exist in the world: a wiki's, under CC BY-SA and therefore carrying attribution
 * and share-alike obligations that would have to be settled in {@code THIRD-PARTY.md} first, and
 * another mod's, which the repository rules forbid outright. Neither is ours to copy. What <i>is</i>
 * ours is what the player finds: every completed round ends with the critter in front of them, so
 * the list builds itself out of gameplay with no provenance question - and a Hypixel patch that adds
 * hiding places is absorbed without a data release.
 *
 * <p><b>A spot is a position and a hit count.</b> The count is the confidence: a place found four
 * times is a stronger claim than one found once, it is shown on the marker, and it breaks ties when
 * two spots are equally close. Positions within {@link #MERGE_RADIUS} of a known one are the same
 * spot - the player is never standing on exactly the same block twice - so the count grows instead
 * of the list.
 *
 * <p>Kept in {@code config/sbs/data/hideyho_spots.json} beside the learned Fairy Souls, whose file
 * this is a close paraphrase of, and global rather than profile-scoped: the Safari's geometry
 * belongs to the game, not to a profile.
 */
public final class HideyhoSpots {

    private static final HideyhoSpots INSTANCE = new HideyhoSpots();

    /** A find this close to a known spot is that spot again, not a new one. */
    private static final double MERGE_RADIUS = 4.0;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** One learned hiding place. */
    public record Spot(int x, int y, int z, int hits) {

        /** Stable id, derived from the position - which is exactly as stable as the spot itself. */
        public String id() {
            return x + "_" + y + "_" + z;
        }

        public BlockPos pos() {
            return new BlockPos(x, y, z);
        }
    }

    /** What is persisted. A class rather than a record: Gson writes into the fields. */
    private static final class Data {
        List<int[]> spots = new ArrayList<>();
    }

    private Data data;

    /** Bumped on every change, so a publisher can notice the set moved without diffing it. */
    private volatile int generation;

    private HideyhoSpots() {
    }

    public static HideyhoSpots getInstance() {
        return INSTANCE;
    }

    private static Path file() {
        return SBSFiles.root().resolve("data").resolve("hideyho_spots.json");
    }

    /** Changes since the last read - the cheap "has the set moved" test. */
    public int generation() {
        return generation;
    }

    /** Every known spot, newest last. Never {@code null}. */
    public synchronized List<Spot> all() {
        List<Spot> out = new ArrayList<>();
        for (int[] entry : data().spots) {
            if (entry != null && entry.length >= 3) {
                out.add(new Spot(entry[0], entry[1], entry[2], entry.length >= 4 ? entry[3] : 1));
            }
        }
        return List.copyOf(out);
    }

    public synchronized int size() {
        return data().spots.size();
    }

    /**
     * Records a place the critter was actually found, or credits the known spot it belongs to.
     *
     * <p>Returns {@code true} when this was a hiding place nobody had seen before, which is what the
     * chat line reports - "one more spot on the map" is the reason a player leaves the feature on
     * while its list is still short.
     */
    public synchronized boolean learn(BlockPos pos) {
        Data data = data();
        for (int i = 0; i < data.spots.size(); i++) {
            int[] entry = data.spots.get(i);
            if (entry == null || entry.length < 3) {
                continue;
            }
            double dx = entry[0] - pos.getX();
            double dy = entry[1] - pos.getY();
            double dz = entry[2] - pos.getZ();
            if (dx * dx + dy * dy + dz * dz <= MERGE_RADIUS * MERGE_RADIUS) {
                int hits = (entry.length >= 4 ? entry[3] : 1) + 1;
                data.spots.set(i, new int[] {entry[0], entry[1], entry[2], hits});
                save();
                return false;
            }
        }
        data.spots.add(new int[] {pos.getX(), pos.getY(), pos.getZ(), 1});
        save();
        return true;
    }

    /** Forgets everything learned - the settings page's escape hatch for a list gone wrong. */
    public synchronized void clear() {
        data().spots.clear();
        save();
    }

    private Data data() {
        if (data == null) {
            data = load();
        }
        return data;
    }

    private static Data load() {
        try {
            Path path = file();
            if (Files.isRegularFile(path)) {
                Data loaded = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8),
                        new TypeToken<Data>() { }.getType());
                if (loaded != null) {
                    if (loaded.spots == null) {
                        loaded.spots = new ArrayList<>();
                    }
                    return loaded;
                }
            }
        } catch (Exception e) {
            // A corrupt cache is the one expected failure here: the feature carries on with an empty
            // list and says so on its page, rather than dying on a file the player cannot read.
            SkyblockSimplifiedSBS.LOGGER.warn(
                    "[SBS][Hideyho] could not read the learned-spots file ({}) - starting empty",
                    e.toString());
        }
        return new Data();
    }

    /** Written on every change: a find is a rare event, and losing one to a crash loses a trip. */
    private void save() {
        generation++;
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn(
                    "[SBS][Hideyho] could not write the learned-spots file ({})", e.toString());
        }
    }
}
