/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.terrain;

import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.mixin.ChunkPacketPositionAccessor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Draws the walk-connected islands beside each other, the way they pretend to be.
 *
 * <p><b>Dormant by default.</b> Every island now sits in a frame of its own ({@link #FRAMES}), so
 * nothing is drawn from anyone else's store and each island shows exactly what its own server
 * sent. The machinery below is intact and one edit away from running again - the frame table is
 * the whole switch.
 *
 * <p><b>What it does when islands share a frame.</b> Standing on one island, the rest of the world
 * is not sent - each is its own mini server - but the terrain files remember what every server
 * showed, and measuring them against each other proved the scanned islands sit in one shared,
 * already-aligned world. So same-frame neighbours' remembered chunks are served at their true
 * positions, with the island underfoot winning any contested position and the richest rendition
 * winning between neighbours. A map outside the active frame is drawn only if the player opts in,
 * at an artificial spot searched out beside the island (the packet is the lever for that:
 * everything inside a chunk packet is chunk-local, so rewriting its x/z re-homes the chunk intact
 * - see {@link ChunkPacketPositionAccessor}).
 *
 * <p><b>Why it is off.</b> Each server renders the shared world faithfully near its own island and
 * loosely far from it, and being shown a neighbour's thinner version of ground you know reads as
 * corruption whichever version is technically "right".
 *
 * <p><b>What this deliberately is not.</b> The translated terrain is scenery. It is real client
 * chunks (the renderer knows no other kind), but it is placed where nothing truly is: waypoints,
 * pathfinding and the SkyBlock map still speak true coordinates, and the fairy-soul scanner is
 * gated off translated chunks so nothing is ever <i>learned</i> from scenery. The layout is
 * computed from the measured boxes at runtime - never hardcoded - and slides further out if it
 * would land on real terrain.
 */
public final class FarTerrainNeighborView {

    /**
     * Every island that shows its neighbours through this view. Order is placement priority twice
     * over: a contested position goes to the earlier member, and the searched placements take the
     * free spots nearest the island underfoot in this order.
     */
    private static final List<String> CHAIN = List.of(
            "Hub", "The Park", "Galatea", "Torrhus Canyon", "Spider's Den", "The End",
            "Crimson Isle", "Gold Mine", "The Farming Islands");

    /**
     * Which maps share a coordinate space. Maps in the same frame draw each other's chunks exactly
     * where they say, offset zero; a map in a frame of its own is not drawn beside its neighbours
     * at all (unless the player opts into the searched beside-the-world placement).
     *
     * <p><b>Currently every island has a frame to itself</b> - see the block above the table - so
     * each island shows only what its own server sent. The measurement below is kept because it is
     * still true and still the thing to restore from; it is not what the module does right now.
     *
     * <p><b>One frame, measured three times over (2026-08-06).</b> Byte-identical unique chunks
     * vote for an offset and winnowed-shingle similarity confirms it; a one-chunk control is the
     * yardstick. The decisive round was CLEAN per-island files (every store captured in one
     * sitting after a full wipe, no shared file anywhere): Hub/Spider's Den median 1.00 with 98%
     * of 246 positions over 0.5 and 107 byte-identical chunks; Hub/Park 47% over 0.5 against a 2%
     * control; Galatea/Spider's Den 39% against 1%; Park/Spider's Den 39% against 4%; the walk
     * chain internally 40-60%. The End and Gold Mine were measured in the pre-wipe round (median
     * 1.00 against Hub and Spider's Den; 434 byte-identical chunks Spider/End). Every signal sits
     * at exactly 0,0 and nothing votes anywhere else: one world.
     *
     * <p><b>Why medians below 1.0 are not misalignment.</b> Each server renders the shared world
     * faithfully near its own island and increasingly loosely far from it, so two renditions of
     * the same position can disagree even though the position is the same place. An earlier
     * measurement over the claim-split shared file read those weak links (0.13-0.18) as two
     * separate frames - the clean files corrected that. The divergence only ever shows where the
     * active island's own store has nothing, because the island underfoot wins every contested
     * position.
     *
     * <p>The full-set round (2026-08-06 late, all ten islands freshly scanned) pinned down the
     * rest: The Farming Islands sits in the frame decisively (0.99 median against the Hub over
     * 440 positions with 46 byte-identical chunks, 0.99 against Gold Mine, 1.00 against Spider's
     * Den) - one server holding both The Barn and Mushroom Desert, so both render from the Hub.
     * It also exposed the CORE group: Hub, Spider's Den, The End, Gold Mine and the Farming
     * Islands agree pairwise at ~1.00 (likely literally one server-side world), while the themed
     * islands (the foraging chain; Crimson Isle at 0.11-0.22, elevated over a 0% control but
     * weak) each diverge away from their own island - which is why contested positions go to the
     * richest rendition, not to a fixed order (see {@link #rebuildQueue}).
     *
     * <p><b>Deep Caverns must never be added:</b> 928 positions shared with the Hub at similarity
     * 0.01 - per the player, it is genuinely an older generation of the map, from before the
     * nether update, stacked at the same coordinates. Drawing it "truly" would pour the old
     * caverns over the present-day Hub.
     */
    // ------------------------------------------------------------------------------------------
    // EVERY ISLAND ON ITS OWN (player's call, 2026-08-07). Each island is given a frame of its
    // own, so no island is ever drawn into another's world and each shows only the terrain its own
    // server sent. The measurement above still stands - what it could not settle is that two
    // servers' renditions of the same ground disagree often enough to be noticed, and being shown
    // a neighbour's version of your island reads as corruption whichever version is "right".
    //
    // TO RESTORE THE SHARED WORLD: delete the block below and uncomment the block above it. That
    // is the only change needed - everything else (serving at true positions, the richest-
    // rendition rule for contested spots, the store cache) is untouched and still works.
    // ------------------------------------------------------------------------------------------

    //  private static final Map<String, Integer> FRAMES = Map.of(
    //          "Hub", 1,
    //          "Spider's Den", 1,
    //          "The End", 1,
    //          "Gold Mine", 1,
    //          "Crimson Isle", 1,
    //          "The Farming Islands", 1,
    //          "The Park", 1,
    //          "Galatea", 1,
    //          "Torrhus Canyon", 1);

    private static final Map<String, Integer> FRAMES = Map.of(
            "Hub", 1,
            "Spider's Den", 2,
            "The End", 3,
            "Gold Mine", 4,
            "Crimson Isle", 5,
            "The Farming Islands", 6,
            "The Park", 7,
            "Galatea", 8,
            "Torrhus Canyon", 9);

    /**
     * Chunks of empty space between neighbouring boxes. Comfortably more than the fairy-soul
     * scanner's reach, so even ungated consumers never touch scenery by accident, and wide enough
     * to read as a sea gap rather than a seam.
     */
    private static final int GAP_CHUNKS = 8;

    /** How far the layout slides per attempt to get clear of real terrain, and how often. */
    private static final int SLIDE_CHUNKS = 4;
    private static final int MAX_SLIDES = 24;

    /** Serve budget per tick - deliberately smaller than the main store's; scenery queues last. */
    private static final long BUDGET_NANOS = 3_000_000L;
    private static final int BUDGET_CHUNKS = 48;

    /**
     * Eviction cadence. Short, because it is also what clears scenery out of the server's way as
     * the player approaches a seam - the standoff ring only helps if it is enforced promptly.
     */
    private static final int DROP_INTERVAL_TICKS = 40;

    private static final FarTerrainNeighborView INSTANCE = new FarTerrainNeighborView();

    /** Neighbour stores, loaded read-only on demand and kept for the session. Main thread only. */
    private final Map<String, FarTerrainStore> stores = new HashMap<>();

    /**
     * Chunk-key offset per neighbour map, computed from the measured boxes. Concurrent for the same
     * reason as {@link #TRANSLATED}: the transfer hook clears it from the network thread while the
     * client thread may be mid-iteration.
     */
    private final Map<String, long[]> offsets = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Every translated position currently live in the level, so it can be evicted and gated.
     * Concurrent because it is written on the client thread but read by {@link #isTranslated} from
     * wherever a consumer happens to sit, and cleared by the server-transfer hook on the network
     * thread - a plain set there was a crash on every travel.
     */
    private static final Set<Long> TRANSLATED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** The serve queue: packed translated-distance ordering, same scheme as the main store's. */
    private volatile long[] order;  // store key of the source chunk; volatile - see onLevelGone
    private String[] orderMap;      // which neighbour map each entry came from
    private int index;
    private int queueCenterX = Integer.MIN_VALUE;
    private int queueCenterZ = Integer.MIN_VALUE;

    private String layoutMap;
    private int tickCounter;
    private int served;

    /** How many neighbour stores were loaded when the current layout was computed. */
    private int placedAtStoreCount;

    /** Last reported serve count, so the progress line is printed only when it changes. */
    private int reportedServed;

    /**
     * How far the laid-out scenery reaches from anywhere on the active island, in chunks - a
     * box-to-box number with no player term in it, so it is CONSTANT per layout. This is what the
     * live window folds in; the player-relative extent recomputed while walking is exactly the
     * jitter that was resetting the renderer every two seconds.
     */
    private volatile int reachChunks;

    private FarTerrainNeighborView() {
    }

    public static FarTerrainNeighborView getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.FarTerrainSettings cfg() {
        return ConfigManager.getInstance().get().farTerrain;
    }

    /** Whether a chunk position holds translated scenery rather than real terrain. */
    public static boolean isTranslated(int chunkX, int chunkZ) {
        return TRANSLATED.contains(FarTerrainStore.key(chunkX, chunkZ));
    }

    /**
     * Gives a position back to real terrain: if scenery holds it, the scenery chunk is dropped and
     * forgotten. Real terrain always outranks scenery - without this, a stale piece of scenery
     * could sit on a position the main store wants to serve and block it forever.
     */
    static boolean reclaim(ClientLevel level, int chunkX, int chunkZ) {
        if (!TRANSLATED.remove(FarTerrainStore.key(chunkX, chunkZ))) {
            return false;
        }
        if (level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) != null) {
            level.getChunkSource().drop(new ChunkPos(chunkX, chunkZ));
        }
        return true;
    }

    /** Where a piece of translated scenery came from: which map, and its true chunk there. */
    record Source(String map, int sourceX, int sourceZ) {
    }

    /**
     * Resolves a translated position back to the map and true chunk it was served from, or
     * {@code null} when no laid-out neighbour accounts for it. Walks the offsets rather than
     * keeping a reverse index - it only runs on an explicit mark keypress.
     */
    Source sourceOf(int chunkX, int chunkZ) {
        for (Map.Entry<String, long[]> entry : offsets.entrySet()) {
            long[] offset = entry.getValue();
            int sourceX = chunkX - (int) offset[0];
            int sourceZ = chunkZ - (int) offset[1];
            FarTerrainStore store = stores.get(entry.getKey());
            if (store != null && store.has(FarTerrainStore.key(sourceX, sourceZ))) {
                return new Source(entry.getKey(), sourceX, sourceZ);
            }
        }
        return null;
    }

    /** The scenery count for the settings line. */
    public String statusLine() {
        if (TRANSLATED.isEmpty()) {
            return "Each island shows only its own terrain";
        }
        return TRANSLATED.size() + " scenery chunk(s) from " + offsets.size() + " neighbour island(s)";
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Driven from the manager's tick, after the main store has been served. All gating lives here:
     * scenery is the lowest-priority work in the module and simply sits out any tick where
     * something more important is happening.
     */
    void tick(Minecraft minecraft, FarTerrainManager manager, String activeMap, ClientLevel level) {
        tickCounter++;
        if (!CHAIN.contains(activeMap)) {
            return;
        }
        // With every island in a frame of its own there is nothing to draw here at all. Answering
        // that first matters: below this line every tick would otherwise re-measure the active
        // store's bounding box (a scan of every remembered chunk) for a layout that can only come
        // out empty, and ensureStores would read every other island's file to hold it unused.
        if (!drawsAnything(activeMap)) {
            if (!TRANSLATED.isEmpty()) {
                releaseAll(level);
            }
            offsets.clear();
            reachChunks = 0;
            layoutMap = null;   // so a re-enabled frame lays out from scratch, not from leftovers
            return;
        }
        if (!activeMap.equals(layoutMap)) {
            releaseAll(level);
            layoutMap = activeMap;
            offsets.clear();
            reachChunks = 0;
            order = null;
        }
        ensureStores(manager, activeMap);
        // Re-layout whenever another store has finished loading since the last pass. A layout
        // computed while a neighbour was still reading from disk is a PARTIAL one, and freezing it
        // silently dropped that island for the rest of the stay - the Hub missing from the Park's
        // horizon because its 5 MB file took a moment longer than the others.
        int ready = loadedStoreCount(activeMap);
        if ((offsets.isEmpty() || ready > placedAtStoreCount) && !computeLayout(manager, activeMap, ready)) {
            return;
        }
        if (offsets.isEmpty()) {
            return;
        }
        // The real island keeps priority, but scenery is not starved behind a long uncapped fill -
        // it takes every fourth tick, which fills the horizon in parallel at a quarter pace.
        if (manager.mainBacklog() && tickCounter % 4 != 0) {
            return;
        }
        serve(minecraft, manager, level);
        if (tickCounter % DROP_INTERVAL_TICKS == 0) {
            dropFarScenery(minecraft, level);
        }
        // "Laid out" was never proof of anything - the offsets logged fine while every chunk was
        // being filtered out of the queue unseen. This reports what actually reached the world.
        if (tickCounter % 200 == 0 && served != reportedServed) {
            reportedServed = served;
            SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Terrain] neighbour view: {} scenery chunks placed, {} live, window {} ch",
                    served, TRANSLATED.size(), FarTerrainManager.liveRadius());
        }
    }

    /**
     * How close to the player scenery may exist - a small ring, deliberately.
     *
     * <p>This was the server's announced radius (32 on Hypixel, so a 34-chunk exclusion) on the
     * theory that the server owns everything inside its own view distance. It does not: the server
     * only ever sends chunks that its island actually has, and a placement is already checked
     * against every real chunk the map's store knows. The oversized ring simply ate the near half
     * of every neighbour - served, evicted on the next sweep, served again, forever (the giveaway
     * was a serve count climbing past 5900 while the live count sat flat at 2400).
     *
     * <p>What remains is a courtesy gap around the player, so a chunk the server sends that this
     * client has never captured cannot land on top of scenery in the moment it appears. The rare
     * late conflict is handled properly by {@link #reclaim}, where real terrain always wins.
     */
    private static final int EXCLUSION_CHUNKS = 6;

    private static int exclusionRadius() {
        return EXCLUSION_CHUNKS;
    }

    /** Opens every other chain member's store, read-only. Loads happen on the IO worker. */
    private void ensureStores(FarTerrainManager manager, String activeMap) {
        for (String map : CHAIN) {
            if (map.equals(activeMap) || stores.containsKey(map)) {
                continue;
            }
            // Only a map that can actually be drawn is worth reading: a store is megabytes, and
            // holding every island's in memory to draw none of them is the whole world in RAM for
            // nothing.
            if (!canDraw(activeMap, map)) {
                continue;
            }
            FarTerrainStore store = new FarTerrainStore(map, false);
            stores.put(map, store);
            manager.submitIoTask(store::load);
        }
    }

    /** Whether {@code map} may be drawn while standing on {@code activeMap}. */
    private static boolean canDraw(String activeMap, String map) {
        if (cfg().unalignedBeside) {
            return true;   // the searched beside-the-world placement takes anyone
        }
        Integer frame = FRAMES.get(activeMap);
        return frame != null && frame.equals(FRAMES.get(map));
    }

    /** Whether any island at all could be drawn from here - the gate on doing any work. */
    private static boolean drawsAnything(String activeMap) {
        for (String map : CHAIN) {
            if (!map.equals(activeMap) && canDraw(activeMap, map)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Places every loaded neighbour's box along the chain row, anchored on the active map's real
     * terrain, and slides any box that would land on real chunks further out. Returns false while
     * the stores it needs are still loading - the layout is only ever computed from full data.
     */
    /** Neighbour stores that are loaded with content - the layout's input set. */
    private int loadedStoreCount(String activeMap) {
        int count = 0;
        for (String map : CHAIN) {
            if (map.equals(activeMap)) {
                continue;
            }
            FarTerrainStore store = stores.get(map);
            if (store != null && store.loaded() && store.size() > 0) {
                count++;
            }
        }
        return count;
    }

    private boolean computeLayout(FarTerrainManager manager, String activeMap, int ready) {
        FarTerrainStore activeStore = manager.activeStore();
        if (activeStore == null || !activeStore.loaded() || activeStore.size() == 0) {
            return false;
        }
        // A fresh layout replaces the old one wholesale - scenery placed under the old offsets
        // would otherwise linger at positions the new layout no longer means.
        if (!offsets.isEmpty()) {
            releaseAll(Minecraft.getInstance().level);
            offsets.clear();
            order = null;
        }
        placedAtStoreCount = ready;
        int activeIndex = CHAIN.indexOf(activeMap);
        // Anchor on the ISLAND underfoot, not the whole store. The main map's store also holds the
        // Farming Islands and the rest at their genuinely distant coordinates - anchoring on its
        // full bounds threw the row hundreds of chunks past the horizon, which read as "the
        // translator does nothing".
        int[] activeBox = null;
        FarTerrainClaims record = manager.claims();
        String island = manager.currentIsland();
        if (record != null && island != null) {
            activeBox = record.boundsOf(island);
        }
        if (activeBox == null) {
            activeBox = boundsOf(activeStore);
        }

        // The same-frame maps first, at their true positions (offset zero - the frames' whole
        // meaning; see FRAMES). Their boxes seed placedBoxes, so the searched placements below
        // cannot land on top of measured ground.
        List<int[]> placedBoxes = new ArrayList<>();
        Integer activeFrame = FRAMES.get(activeMap);
        for (int i = 0; i < CHAIN.size(); i++) {
            String map = CHAIN.get(i);
            if (i == activeIndex || activeFrame == null
                    || !activeFrame.equals(FRAMES.get(map))) {
                continue;
            }
            FarTerrainStore store = stores.get(map);
            if (store == null || !store.loaded() || store.size() == 0) {
                continue;
            }
            offsets.put(map, new long[]{0, 0});
            placedBoxes.add(boundsOf(store));
        }
        // Unmeasured maps are drawn only when the player asked for them, at the nearest free spot
        // beside the island. Off by default: a searched spot is a made-up position, and a made-up
        // position is precisely what reads as "the offset is wrong" - worse, right after a wipe
        // the search runs against a nearly empty store, so "free" ground may simply be ground the
        // world has not been re-captured on yet.
        if (cfg().unalignedBeside) {
            for (int i = 0; i < CHAIN.size(); i++) {
                String map = CHAIN.get(i);
                if (i == activeIndex || offsets.containsKey(map)) {
                    continue;
                }
                place(map, activeStore, activeBox, placedBoxes);
            }
        }
        if (!offsets.isEmpty()) {
            reachChunks = computeReach(activeBox);
            StringBuilder line = new StringBuilder();
            for (Map.Entry<String, long[]> entry : offsets.entrySet()) {
                if (line.length() > 0) {
                    line.append(", ");
                }
                if (entry.getValue()[0] == 0 && entry.getValue()[1] == 0) {
                    line.append(entry.getKey()).append(" at true position");
                } else {
                    line.append(entry.getKey()).append(" by ").append(entry.getValue()[0])
                            .append(',').append(entry.getValue()[1]);
                }
            }
            SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Terrain] neighbour view laid out from measured boxes - offsets (chunks): "
                            + "{} - reach {} ch", line, reachChunks);
        }
        return !offsets.isEmpty();
    }

    /** The layout's constant reach: worst-case distance from the active box to any scenery corner. */
    private int computeReach(int[] activeBox) {
        int reach = 0;
        for (Map.Entry<String, long[]> entry : offsets.entrySet()) {
            FarTerrainStore store = stores.get(entry.getKey());
            if (store == null || !store.loaded() || store.size() == 0) {
                continue;
            }
            int[] box = boundsOf(store);
            long[] offset = entry.getValue();
            reach = Math.max(reach, spanFrom(activeBox, new int[]{
                    box[0] + (int) offset[0], box[1] + (int) offset[1],
                    box[2] + (int) offset[0], box[3] + (int) offset[1]}));
        }
        return reach;
    }

    /** The constant per-layout reach, for the live window; {@code 0} while nothing is laid out. */
    public int reachChunks() {
        return reachChunks;
    }

    /**
     * Finds the closest clear spot around the anchor for one neighbour: all four directions are
     * tried, each slid outward past whatever is in the way (the active store's real chunks AND
     * scenery already placed), and the direction that ends up nearest the anchor wins. Nearest
     * matters more than any fixed compass aesthetic, because distance decides whether the scenery
     * is inside the live window at all - a beautiful row past the horizon is indistinguishable
     * from the feature not working.
     */
    private void place(String map, FarTerrainStore activeStore, int[] anchorBox,
                       List<int[]> placedBoxes) {
        FarTerrainStore store = stores.get(map);
        if (store == null || !store.loaded() || store.size() == 0) {
            return;
        }
        int[] box = boundsOf(store);
        int anchorCenterX = (anchorBox[0] + anchorBox[2]) / 2;
        int anchorCenterZ = (anchorBox[1] + anchorBox[3]) / 2;
        int boxCenterX = (box[0] + box[2]) / 2;
        int boxCenterZ = (box[1] + box[3]) / 2;

        long[] best = null;
        int[] bestBox = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int direction = 0; direction < 4; direction++) {
            // 0 north, 1 south, 2 west, 3 east; the off-axis is centred on the anchor.
            int offsetX = direction < 2 ? anchorCenterX - boxCenterX
                    : (direction == 2 ? anchorBox[0] - GAP_CHUNKS - box[2]
                            : anchorBox[2] + GAP_CHUNKS - box[0]);
            int offsetZ = direction >= 2 ? anchorCenterZ - boxCenterZ
                    : (direction == 0 ? anchorBox[1] - GAP_CHUNKS - box[3]
                            : anchorBox[3] + GAP_CHUNKS - box[1]);
            for (int slide = 0; slide < MAX_SLIDES; slide++) {
                if (isClear(activeStore, placedBoxes, box, offsetX, offsetZ)) {
                    int[] translated = {box[0] + offsetX, box[1] + offsetZ,
                            box[2] + offsetX, box[3] + offsetZ};
                    // Scored by SPAN, not by the gap between the boxes. The span is what has to fit
                    // inside the live window for any of this to be drawn, so optimising the gap
                    // instead produced snug-looking layouts whose far corners sat 78 chunks out and
                    // were discarded wholesale.
                    int distance = spanFrom(anchorBox, translated);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = new long[]{offsetX, offsetZ};
                        bestBox = translated;
                    }
                    break;
                }
                switch (direction) {
                    case 0 -> offsetZ -= SLIDE_CHUNKS;
                    case 1 -> offsetZ += SLIDE_CHUNKS;
                    case 2 -> offsetX -= SLIDE_CHUNKS;
                    default -> offsetX += SLIDE_CHUNKS;
                }
            }
        }
        if (best == null) {
            SkyblockSimplifiedSBS.LOGGER.warn(
                    "[SBS][Terrain] could not place '{}' clear of real terrain - not drawn", map);
            return;
        }
        offsets.put(map, best);
        placedBoxes.add(bestBox);
    }

    /**
     * The worst-case span from anywhere on the anchor box to anywhere on {@code box} - i.e. the
     * live-window radius this placement demands. The single number that decides whether a piece of
     * scenery can be served and drawn at all.
     */
    private static int spanFrom(int[] anchor, int[] box) {
        int dx = Math.max(Math.abs(box[0] - anchor[2]), Math.abs(box[2] - anchor[0]));
        int dz = Math.max(Math.abs(box[1] - anchor[3]), Math.abs(box[3] - anchor[1]));
        return Math.max(dx, dz);
    }

    /** Clear of the active store's real chunks and (GAP-padded) of all scenery already placed. */
    private boolean isClear(FarTerrainStore activeStore, List<int[]> placedBoxes, int[] box,
                            int offsetX, int offsetZ) {
        int minX = box[0] + offsetX;
        int minZ = box[1] + offsetZ;
        int maxX = box[2] + offsetX;
        int maxZ = box[3] + offsetZ;
        for (int[] placed : placedBoxes) {
            if (minX <= placed[2] + GAP_CHUNKS && maxX >= placed[0] - GAP_CHUNKS
                    && minZ <= placed[3] + GAP_CHUNKS && maxZ >= placed[1] - GAP_CHUNKS) {
                return false;
            }
        }
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (activeStore.has(FarTerrainStore.key(x, z))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** {@code [minX, minZ, maxX, maxZ]} over a store's keys. */
    private static int[] boundsOf(FarTerrainStore store) {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (long key : store.keys()) {
            int x = FarTerrainStore.keyX(key);
            int z = FarTerrainStore.keyZ(key);
            minX = Math.min(minX, x);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxZ = Math.max(maxZ, z);
        }
        return new int[]{minX, minZ, maxX, maxZ};
    }

    // ------------------------------------------------------------------ serving

    private void serve(Minecraft minecraft, FarTerrainManager manager, ClientLevel level) {
        var player = minecraft.player;
        if (player == null) {
            return;
        }
        int centerX = player.chunkPosition().x();
        int centerZ = player.chunkPosition().z();
        boolean exhausted = order == null || index >= order.length;
        if (order == null
                || Math.abs(centerX - queueCenterX) >= 2 || Math.abs(centerZ - queueCenterZ) >= 2
                || (exhausted && tickCounter % 200 == 0)) {
            rebuildQueue(level, centerX, centerZ);
        }
        // Snapshot: the transfer hook may null the queue from the network thread mid-loop.
        long[] queue = order;
        String[] queueMaps = orderMap;
        if (queue == null || queueMaps == null || queueMaps.length != queue.length) {
            return;
        }
        long deadline = System.nanoTime() + BUDGET_NANOS;
        int budget = BUDGET_CHUNKS;
        while (budget > 0 && index < queue.length && System.nanoTime() < deadline) {
            int i = index++;
            long sourceKey = queue[i];
            FarTerrainStore store = stores.get(queueMaps[i]);
            long[] offset = offsets.get(queueMaps[i]);
            if (store == null || offset == null) {
                continue;
            }
            int targetX = FarTerrainStore.keyX(sourceKey) + (int) offset[0];
            int targetZ = FarTerrainStore.keyZ(sourceKey) + (int) offset[1];
            int standoff = exclusionRadius();
            if (Math.max(Math.abs(targetX - centerX), Math.abs(targetZ - centerZ)) <= standoff) {
                continue;   // inside the server's own bubble - its position, not ours
            }
            if (level.getChunkSource().getChunk(targetX, targetZ, ChunkStatus.FULL, false) != null) {
                continue;   // already there - either scenery from earlier or, worse, real terrain
            }
            byte[] raw = store.get(sourceKey);
            if (raw == null) {
                continue;
            }
            budget--;
            ClientboundLevelChunkWithLightPacket packet;
            try {
                packet = ClientboundLevelChunkWithLightPacket.STREAM_CODEC.decode(
                        new RegistryFriendlyByteBuf(
                                Unpooled.wrappedBuffer(raw), level.registryAccess()));
            } catch (Throwable t) {
                store.remove(sourceKey);   // stale bytes; scenery is not worth a retry ladder
                continue;
            }
            // The relocation: two ints, and the whole chunk - blocks, light, block entities - lands
            // in the scenery slot instead of on top of the real island.
            ((ChunkPacketPositionAccessor) (Object) packet).skyblockSimplified$setX(targetX);
            ((ChunkPacketPositionAccessor) (Object) packet).skyblockSimplified$setZ(targetZ);
            if (manager.injectPacket(minecraft, level, packet, targetX, targetZ)) {
                TRANSLATED.add(FarTerrainStore.key(targetX, targetZ));
                served++;
            }
        }
    }

    /** One rendition's claim on a contested position - see {@link #rebuildQueue}. */
    private record Candidate(long sourceKey, String map, int size, int centerDist) {
    }

    /**
     * Whether a challenger rendition beats the current holder of a position: clearly richer
     * content wins (a >10% size margin), otherwise the island whose own box sits nearer.
     */
    private static boolean wins(int size, int centerDist, Candidate cur) {
        if ((long) size * 10 > (long) cur.size() * 11) {
            return true;
        }
        if ((long) cur.size() * 10 > (long) size * 11) {
            return false;
        }
        return centerDist < cur.centerDist();
    }

    /** All missing scenery within the live window, nearest first by translated position. */
    private void rebuildQueue(ClientLevel level, int centerX, int centerZ) {
        queueCenterX = centerX;
        queueCenterZ = centerZ;
        int radius = FarTerrainManager.liveRadius();
        // ONE rendition per target position. Every server renders the shared world faithfully
        // near its own island and sparsely far away, so where renditions compete the one with
        // the most content is the authority - payload size is the fidelity signal - and
        // near-ties go to the island whose box center sits closest. Chain order used to settle
        // this, which let the Park's sparse copy of its neighbours' band overwrite Spider's Den
        // and The End THEMSELVES, and every store-load relayout re-fought the same positions,
        // which read in game as chunks being deleted.
        Map<Long, Candidate> best = new HashMap<>();
        for (String mapName : CHAIN) {
            long[] offset = offsets.get(mapName);
            FarTerrainStore store = stores.get(mapName);
            if (offset == null || store == null) {
                continue;
            }
            int[] box = boundsOf(store);
            int ownCenterX = (box[0] + box[2]) / 2 + (int) offset[0];
            int ownCenterZ = (box[1] + box[3]) / 2 + (int) offset[1];
            for (long key : store.keys()) {
                int tx = FarTerrainStore.keyX(key) + (int) offset[0];
                int tz = FarTerrainStore.keyZ(key) + (int) offset[1];
                if (Math.abs(tx - centerX) > radius || Math.abs(tz - centerZ) > radius) {
                    continue;
                }
                if (level.getChunkSource().getChunk(tx, tz, ChunkStatus.FULL, false) != null) {
                    continue;
                }
                int size = store.sizeOf(key);
                int centerDist = Math.max(Math.abs(tx - ownCenterX), Math.abs(tz - ownCenterZ));
                long target = FarTerrainStore.key(tx, tz);
                Candidate cur = best.get(target);
                if (cur == null || wins(size, centerDist, cur)) {
                    best.put(target, new Candidate(key, mapName, size, centerDist));
                }
            }
        }
        int entryIndex = best.size();
        List<long[]> packed = new ArrayList<>(entryIndex);
        List<String> maps = new ArrayList<>(entryIndex);
        for (Map.Entry<Long, Candidate> entry : best.entrySet()) {
            long dx = FarTerrainStore.keyX(entry.getKey()) - centerX;
            long dz = FarTerrainStore.keyZ(entry.getKey()) - centerZ;
            packed.add(new long[]{(dx * dx + dz * dz), entry.getValue().sourceKey()});
            maps.add(entry.getValue().map());
        }
        Integer[] sorted = new Integer[entryIndex];
        for (int i = 0; i < entryIndex; i++) {
            sorted[i] = i;
        }
        java.util.Arrays.sort(sorted, (a, b) -> Long.compare(packed.get(a)[0], packed.get(b)[0]));
        long[] keys = new long[entryIndex];
        String[] keyMaps = new String[entryIndex];
        for (int i = 0; i < entryIndex; i++) {
            keys[i] = packed.get(sorted[i])[1];
            keyMaps[i] = maps.get(sorted[i]);
        }
        // Maps first, then the volatile queue - a reader that sees the queue sees matching maps.
        orderMap = keyMaps;
        order = keys;
        index = 0;
    }

    /** How far the laid-out scenery reaches from the player - folded into the uncapped extent. */
    int extentFrom(int centerX, int centerZ) {
        int extent = 0;
        for (Map.Entry<String, long[]> entry : offsets.entrySet()) {
            FarTerrainStore store = stores.get(entry.getKey());
            if (store == null || !store.loaded() || store.size() == 0) {
                continue;
            }
            int[] box = boundsOf(store);
            long[] offset = entry.getValue();
            int far = (int) Math.max(
                    Math.max(Math.abs(box[0] + offset[0] - centerX),
                            Math.abs(box[2] + offset[0] - centerX)),
                    Math.max(Math.abs(box[1] + offset[1] - centerZ),
                            Math.abs(box[3] + offset[1] - centerZ)));
            extent = Math.max(extent, far);
        }
        return extent;
    }

    // ------------------------------------------------------------------ eviction

    /** Scenery the player moved away from goes first - it was always the cheapest thing held. */
    private void dropFarScenery(Minecraft minecraft, ClientLevel level) {
        if (minecraft.player == null) {
            return;
        }
        int centerX = minecraft.player.chunkPosition().x();
        int centerZ = minecraft.player.chunkPosition().z();
        int limit = FarTerrainManager.liveRadius() + 4;
        int standoff = exclusionRadius();
        TRANSLATED.removeIf(key -> {
            int x = FarTerrainStore.keyX(key);
            int z = FarTerrainStore.keyZ(key);
            int distance = Math.max(Math.abs(x - centerX), Math.abs(z - centerZ));
            // Too far to keep, or close enough that the server's bubble is about to fight us for
            // the position - scenery yields in both directions.
            if (distance <= limit && distance > standoff) {
                return false;
            }
            if (level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) != null) {
                level.getChunkSource().drop(new ChunkPos(x, z));
            }
            return true;
        });
    }

    /**
     * Unloads every piece of scenery. Called when the module goes off, before a server transfer's
     * teardown, and on a map change - phantom chunks must never outlive the context that placed
     * them, because nothing else knows they are not real.
     */
    void releaseAll(ClientLevel level) {
        if (level != null) {
            for (long key : TRANSLATED) {
                int x = FarTerrainStore.keyX(key);
                int z = FarTerrainStore.keyZ(key);
                if (level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) != null) {
                    level.getChunkSource().drop(new ChunkPos(x, z));
                }
            }
        }
        TRANSLATED.clear();
        order = null;
        index = 0;
    }

    /**
     * Drops one cached read-only store so the next layout re-reads its file - called whenever the
     * player leaves that map, because its file was just rewritten with the stay's captures.
     *
     * <p>Without this the cache is session-long, and a session that started with a missing or
     * stale file keeps that read forever: the player who hand-deleted the terrain files mid-game
     * and re-scanned every island got NO neighbours for the rest of the session, because every
     * cache entry was an empty read taken the moment the files were gone. The re-read costs one
     * file load on the IO lane, ordered after the save that made it worth taking.
     */
    void refreshStore(String map) {
        stores.remove(map);
    }

    /**
     * Drops the loaded neighbour stores as well as their scenery, so the next layout re-reads them
     * from disk. Needed after the files are deleted: the stores hold the whole map in memory, and
     * without this the neighbours would keep being drawn from a file that no longer exists.
     */
    void forgetStores(ClientLevel level) {
        releaseAll(level);
        stores.clear();
        offsets.clear();
        reachChunks = 0;
        layoutMap = null;
        placedAtStoreCount = 0;
        served = 0;
        reportedServed = 0;
    }

    /** Level went away entirely - the chunks died with it, only the bookkeeping needs clearing. */
    void onLevelGone() {
        TRANSLATED.clear();
        order = null;
        index = 0;
        layoutMap = null;
        offsets.clear();
        reachChunks = 0;
    }
}
