/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.fairysouls.logic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.BlockPos;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.keybind.IslandCatalog;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.helper.fairysouls.model.FairySoul;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The souls this client has <b>seen with its own eyes</b> - the catalogue that fills itself.
 *
 * <p>The module was designed around a curated coordinate file, and that file is empty: no souls,
 * no waypoints, no routing, however correct the code around it is. But a Fairy Soul is not
 * invisible - it is a placed player-head whose skin is the same for every soul in the game, and
 * skull blocks arrive with their full skin data in the chunk packet. So the world itself is a
 * coordinate source: learn what a soul looks like once, and every soul in any chunk the client
 * loads can be catalogued exactly, no curated data required.
 *
 * <p><b>The skin is learned, never assumed.</b> Hard-coding the skin's base64 would be a guess
 * about data Hypixel controls; instead the first collection line the player triggers identifies
 * one skull as certainly a soul (the "certainty or nothing" rule the whole module runs on), and
 * its skin becomes the reference. From then on the scanner does the walking.
 *
 * <p>Learned souls are second-class on purpose: a curated soul within two blocks supersedes the
 * learned one, so if the coordinate file ever arrives - hand-authored or from the backend - it
 * takes over seamlessly, ids and all. Until then, learned ids are derived from the position,
 * which is exactly as stable as the soul itself.
 *
 * <p><b>2026-09-26: this learned the wrong skin.</b> The play instance's file held one texture
 * ({@code .../texture/5f466be5...}) and exactly two "souls" on each of four islands, at the SAME two
 * coordinates every time - a pair of decorative heads, taught by a single collection that happened
 * to have one decoration within reach. Three rules now stand between a decoration and the
 * catalogue: a skin needs two agreeing collections at separate sites ({@link SoulSkinLessons}); on
 * an island the curated catalogue covers, a head that is not within {@link #PLAUSIBLE_RADIUS} of a
 * curated soul can neither teach nor be catalogued; and {@link #VERSION} drops files written under
 * the old rule. And the premise was wrong: a soul is NOT a placed skull but an invisible armor
 * stand wearing the head (probed 2026-09-26 at three souls) - see {@code FairySoulTracker.nearbyHeads}.
 */
public final class FairySoulLearned {

    private static final FairySoulLearned INSTANCE = new FairySoulLearned();

    /** A learned soul this close to a curated one IS that soul - the curated entry wins. */
    private static final double DUPLICATE_RADIUS = 2.0;

    /**
     * A head further than this from every curated soul is not a soul, on an island the curated file
     * covers. The wiki coordinates are the soul's block; three blocks absorbs rounding and a stand's
     * offset without reaching the next decoration.
     */
    public static final double PLAUSIBLE_RADIUS = 3.0;

    /**
     * Learned-file format. 1 (a file with no version field) = learned from a single collection, the
     * rule that taught a decoration's skin; anything below 2 is dropped on load.
     */
    static final int VERSION = 2;

    private static final Gson GSON = new GsonBuilder().create();

    /** What is persisted: the reference skin, pending lessons, and per-island soul positions. */
    static final class Data {
        /**
         * No initialiser: Gson runs the no-arg constructor, so an initialised field would make a
         * file WITHOUT it read as current. Null = a v1 file; new data goes through {@link #fresh()}.
         */
        Integer version;
        String texture = "";
        Map<String, List<int[]>> lessons = new HashMap<>();
        Map<String, List<int[]>> souls = new HashMap<>();
        /**
         * Learned positions a soul line has confirmed (the player collected, or was told they had
         * already found, the soul there). On an island with no curated souls only these are drawn:
         * a head that was merely seen stays a candidate. Null in files written before this field.
         */
        Map<String, List<int[]>> confirmed = new HashMap<>();
        /** Set by {@link #parse} when it dropped instanced-island entries, so the file is rewritten. */
        transient boolean pruned;

        static Data fresh() {
            Data d = new Data();
            d.version = VERSION;
            return d;
        }

        int versionOrLegacy() {
            return version == null ? 1 : version;
        }
    }

    private Data data;
    private boolean dirty;

    /** Bumped on every change, so the database's merged-list cache knows to rebuild. */
    private volatile int generation;

    private FairySoulLearned() {
    }

    public static FairySoulLearned getInstance() {
        return INSTANCE;
    }

    private static Path file() {
        return SBSFiles.root().resolve("data").resolve("fairysouls_learned.json");
    }

    private synchronized Data data() {
        if (data == null) {
            data = load();
            saveIfDirty();
        }
        return data;
    }

    private Data load() {
        try {
            Path path = file();
            if (Files.isRegularFile(path)) {
                Data loaded = parse(Files.readString(path, StandardCharsets.UTF_8));
                if (loaded.versionOrLegacy() < VERSION) {
                    // Rewrite at once, so the wrong skin cannot come back from disk next launch.
                    loaded.version = VERSION;
                    dirty = true;
                }
                if (loaded.pruned) {
                    dirty = true;   // the instanced-island entries must not come back from disk
                }
                return loaded;
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn(
                    "[SBS][FairySouls] could not read the learned-souls file ({}) - starting empty",
                    e.toString());
        }
        return Data.fresh();
    }

    /**
     * Reads a learned file. A file from before {@link #VERSION} comes back EMPTY, still carrying its
     * old version so the caller knows to rewrite it: its skin was learned from one collection, and
     * on the one instance that has such a file, that skin is a decoration's.
     */
    static Data parse(String json) {
        Data loaded = GSON.fromJson(json, new TypeToken<Data>() { }.getType());
        if (loaded == null) {
            return Data.fresh();
        }
        if (loaded.versionOrLegacy() < VERSION) {
            int souls = 0;
            if (loaded.souls != null) {
                for (List<int[]> list : loaded.souls.values()) {
                    souls += list == null ? 0 : list.size();
                }
            }
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][FairySouls] learned soul data reset: wrong "
                    + "skin (v{} file, {} learned soul(s) dropped) - re-learning under the "
                    + "two-collection rule", loaded.versionOrLegacy(), souls);
            Data reset = Data.fresh();
            reset.version = loaded.versionOrLegacy();
            return reset;
        }
        if (loaded.texture == null) {
            loaded.texture = "";
        }
        if (loaded.lessons == null) {
            loaded.lessons = new HashMap<>();
        }
        if (loaded.souls == null) {
            loaded.souls = new HashMap<>();
        }
        if (loaded.confirmed == null) {
            loaded.confirmed = new HashMap<>();
        }
        dropInstanced(loaded);
        return loaded;
    }

    /**
     * Removes everything learned on a per-player instance (Private Island, Garden...). A head there
     * belongs to one player's copy; stored under the island name it showed up on everyone's.
     */
    private static void dropInstanced(Data data) {
        for (String island : new ArrayList<>(data.souls.keySet())) {
            if (!IslandCatalog.isInstanced(island)) {
                continue;
            }
            List<int[]> dropped = data.souls.remove(island);
            data.confirmed.remove(island);
            data.pruned = true;
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][FairySouls] dropped {} learned soul(s) on instanced "
                    + "island {}", dropped == null ? 0 : dropped.size(), island);
        }
        data.confirmed.keySet().removeIf(island -> {
            boolean instanced = IslandCatalog.isInstanced(island);
            data.pruned |= instanced;
            return instanced;
        });
    }

    /** Writes pending changes; called after a scan pass or a learn event, not per soul. */
    public synchronized void saveIfDirty() {
        if (!dirty || data == null) {
            return;
        }
        dirty = false;
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (Exception e) {
            dirty = true;   // keep trying on later saves rather than silently losing the catalogue
            SkyblockSimplifiedSBS.LOGGER.warn(
                    "[SBS][FairySouls] could not write the learned-souls file ({})", e.toString());
        }
    }

    // ------------------------------------------------------------------ the reference skin

    /** The learned soul skin's texture value, or {@code ""} while no soul has taught it yet. */
    public synchronized String texture() {
        return data().texture == null ? "" : data().texture;
    }

    public boolean textureKnown() {
        return !texture().isEmpty();
    }

    /**
     * Folds one collection's heads into the lessons, and adopts a skin once
     * {@link SoulSkinLessons} says two separate collections agree on it. Once a skin is known this
     * does nothing: the skin is the same for every soul.
     *
     * @return what the collection did, for the caller's log line
     */
    public synchronized SoulSkinLessons.Outcome observeCollection(
            List<SoulSkinLessons.Candidate> candidates) {
        if (textureKnown()) {
            return SoulSkinLessons.Outcome.NONE;
        }
        SoulSkinLessons lessons = new SoulSkinLessons(data().lessons);
        SoulSkinLessons.Outcome outcome = lessons.observe(candidates);
        if (outcome == SoulSkinLessons.Outcome.LESSON_RECORDED) {
            dirty = true;
        } else if (outcome == SoulSkinLessons.Outcome.ADOPTED) {
            data().texture = candidates.getFirst().texture();
            data().lessons.clear();
            dirty = true;
            generation++;
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][FairySouls] soul skin learned - {} separate "
                    + "collections agreed; souls in loaded chunks will now be catalogued",
                    SoulSkinLessons.REQUIRED_LESSONS);
        }
        saveIfDirty();
        return outcome;
    }

    /**
     * Whether a head at this position could be a soul. On an island the curated file covers (any
     * curated soul at all - the wiki import is per island and complete), it must sit within
     * {@link #PLAUSIBLE_RADIUS} of one. An island with no curated souls cannot be checked, so it is
     * allowed - that is the only place a learned soul adds anything anyway.
     */
    public static boolean plausible(int x, int y, int z, List<FairySoul> curated) {
        if (curated == null || curated.isEmpty()) {
            return true;
        }
        for (FairySoul soul : curated) {
            double dx = soul.x - x;
            double dy = soul.y - y;
            double dz = soul.z - z;
            if (Math.sqrt(dx * dx + dy * dy + dz * dz) <= PLAUSIBLE_RADIUS) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ learned positions

    /** Positions already logged as implausible, so the 2s scan does not repeat the line forever. */
    private final java.util.Set<Long> rejectedLogged = new java.util.HashSet<>();

    /**
     * Records a seen soul; returns true when it is new. Positions are exact, so exact-match. A head
     * wearing the soul skin away from every curated soul is refused and logged: either Hypixel moved
     * a soul (the data file needs a refresh) or the learned skin is wrong again.
     */
    public synchronized boolean add(String island, BlockPos pos, List<FairySoul> curated) {
        if (island == null || island.isBlank() || pos == null || IslandCatalog.isInstanced(island)) {
            return false;
        }
        if (!plausible(pos.getX(), pos.getY(), pos.getZ(), curated)) {
            if (rejectedLogged.add(pos.asLong())) {
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][FairySouls] head with the soul skin at "
                        + "({}, {}, {}) on {} is not near any catalogued soul - suspicious, not "
                        + "shown", pos.getX(), pos.getY(), pos.getZ(), island);
            }
            return false;
        }
        if (!addTo(data(), island, pos.getX(), pos.getY(), pos.getZ())) {
            return false;
        }
        dirty = true;
        generation++;
        return true;
    }

    /** The storage half of {@link #add}, on a given data object (tested without the file). */
    static boolean addTo(Data data, String island, int x, int y, int z) {
        if (IslandCatalog.isInstanced(island)) {
            return false;
        }
        List<int[]> list = data.souls.computeIfAbsent(island, k -> new ArrayList<>());
        if (indexOf(list, x, y, z) >= 0) {
            return false;
        }
        list.add(new int[]{x, y, z});
        return true;
    }

    private static int indexOf(List<int[]> list, int x, int y, int z) {
        for (int i = 0; i < list.size(); i++) {
            int[] e = list.get(i);
            if (e.length == 3 && e[0] == x && e[1] == y && e[2] == z) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ confirmation

    /** How far a soul line's position (click or player) may be from a learned candidate. */
    static final double CONFIRM_RADIUS = 2.0;

    /** The learned candidate nearest {@code point} within {@link #CONFIRM_RADIUS}, or null. */
    static int[] candidateNear(Data data, String island, double[] point) {
        List<int[]> list = data.souls.get(island);
        if (list == null || point == null) {
            return null;
        }
        int[] best = null;
        double bestD = CONFIRM_RADIUS;
        for (int[] e : list) {
            if (e.length != 3) {
                continue;
            }
            double d = Math.sqrt(sq(e[0] + 0.5 - point[0]) + sq(e[1] + 0.5 - point[1]) + sq(e[2] + 0.5 - point[2]));
            if (d <= bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    /** Marks the candidate at {@code entry} as a real soul. Returns whether it was newly confirmed. */
    static boolean confirmIn(Data data, String island, int[] entry) {
        List<int[]> list = data.confirmed.computeIfAbsent(island, k -> new ArrayList<>());
        if (indexOf(list, entry[0], entry[1], entry[2]) >= 0) {
            return false;
        }
        list.add(entry.clone());
        return true;
    }

    /** Whether the candidate at {@code entry} has been confirmed by a soul line. */
    static boolean isConfirmed(Data data, String island, int[] entry) {
        List<int[]> list = data.confirmed.get(island);
        return list != null && indexOf(list, entry[0], entry[1], entry[2]) >= 0;
    }

    /**
     * A soul line ("You found a Fairy Soul!" / "already found") at {@code point}: the learned
     * candidate there, if any, is confirmed. Returns whether one was.
     */
    public synchronized boolean confirmNear(String island, double[] point) {
        if (island == null || IslandCatalog.isInstanced(island)) {
            return false;
        }
        int[] entry = candidateNear(data(), island, point);
        if (entry == null || !confirmIn(data(), island, entry)) {
            return false;
        }
        dirty = true;
        generation++;
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][FairySouls] learned soul at ({}, {}, {}) on {} confirmed by "
                + "a soul line", entry[0], entry[1], entry[2], island);
        saveIfDirty();
        return true;
    }

    /** The unconfirmed learned candidate a click at {@code point} hit, or null. */
    public synchronized int[] unconfirmedNear(String island, double[] point) {
        if (island == null) {
            return null;
        }
        int[] entry = candidateNear(data(), island, point);
        return entry == null || isConfirmed(data(), island, entry) ? null : entry.clone();
    }

    /** Removes a candidate that was clicked and answered with no soul line. */
    public synchronized void removeCandidate(String island, int[] entry) {
        List<int[]> list = data().souls.get(island);
        int i = list == null ? -1 : indexOf(list, entry[0], entry[1], entry[2]);
        if (i < 0 || isConfirmed(data(), island, entry)) {
            return;
        }
        list.remove(i);
        dirty = true;
        generation++;
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][FairySouls] learned candidate at ({}, {}, {}) on {} removed - "
                + "clicked, and no soul line followed", entry[0], entry[1], entry[2], island);
        saveIfDirty();
    }

    private static double sq(double v) {
        return v * v;
    }

    /**
     * The learned souls on {@code island} as {@link FairySoul}s, minus any that a curated soul
     * already covers. The id is derived from the position - as permanent as the soul, and never
     * colliding with the curated files' "island-NNN" scheme.
     */
    public synchronized List<FairySoul> forIsland(String island, List<FairySoul> curated) {
        return forIsland(data(), island, curated);
    }

    /**
     * The drawable learned souls of {@code island}. Never any on an instanced island. Where the
     * curated file has no souls for the island, only candidates a soul line confirmed: there is
     * nothing else to check a head against, and a decoration wearing the skin is still not a soul.
     */
    static List<FairySoul> forIsland(Data data, String island, List<FairySoul> curated) {
        if (IslandCatalog.isInstanced(island)) {
            return List.of();
        }
        List<int[]> list = data.souls.get(island);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        boolean uncatalogued = curated == null || curated.isEmpty();
        List<FairySoul> out = new ArrayList<>(list.size());
        outer:
        for (int[] entry : list) {
            if (entry.length != 3) {
                continue;
            }
            // add() already refuses these; this also covers a file written before it did.
            if (!plausible(entry[0], entry[1], entry[2], curated)) {
                continue;
            }
            if (uncatalogued && !isConfirmed(data, island, entry)) {
                continue;   // seen only: a candidate, not drawn
            }
            for (FairySoul authored : curated) {
                if (authored.centre().distanceTo(
                        new net.minecraft.world.phys.Vec3(entry[0] + 0.5, entry[1] + 0.5,
                                entry[2] + 0.5)) <= DUPLICATE_RADIUS) {
                    continue outer;
                }
            }
            FairySoul soul = new FairySoul();
            soul.id = "learned_" + entry[0] + "_" + entry[1] + "_" + entry[2];
            soul.x = entry[0];
            soul.y = entry[1];
            soul.z = entry[2];
            soul.area = "seen nearby";
            soul.island = island;
            soul.learned = true;
            out.add(soul);
        }
        return out;
    }

    /** How many souls have been learned in total, for the settings status line. */
    public synchronized int count() {
        int total = 0;
        for (List<int[]> list : data().souls.values()) {
            total += list.size();
        }
        return total;
    }

    /** Cache key for merged lists: changes whenever anything here changes. */
    public int generation() {
        return generation;
    }

    /** The settings line: whether the scanner is armed, and what it has found so far. */
    public String status() {
        if (!textureKnown()) {
            return "Scanner idle - collect (or re-touch) two different souls to teach it the look";
        }
        return "Scanner armed - " + count() + " soul(s) learned by sight";
    }
}
