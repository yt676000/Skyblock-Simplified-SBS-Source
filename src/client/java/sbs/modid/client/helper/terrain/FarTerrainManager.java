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
import net.minecraft.world.level.chunk.status.ChunkStatus;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.core.location.SkyBlockLocation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Far Terrain: remembers every chunk the server sends on the supported islands and serves the
 * remembered ones back to the renderer, so the island stays visible far beyond the handful of
 * chunks Hypixel actually streams around you - and is already there the moment you rejoin.
 *
 * <p><b>How it works.</b> Hypixel only keeps a small bubble of chunks loaded around the player;
 * everything outside it is taken away again and the island ends in fog a few dozen blocks out.
 * This module does three things about that:
 * <ol>
 *   <li><b>Capture</b> - every chunk packet that arrives on a supported island is re-encoded to its
 *       raw network bytes and stored in that island's {@link FarTerrainStore} (one file per island
 *       under {@code config/sbs/render/}). New arrivals overwrite old captures, so the store tracks
 *       the terrain as it was last seen.</li>
 *   <li><b>Keep</b> - when the server tells the client to forget a chunk that is still within the
 *       configured radius, the forget is vetoed and the chunk simply stays loaded. Nothing needs to
 *       be rebuilt later, it never left.</li>
 *   <li><b>Serve</b> - chunks the client does not have (because you just joined, or walked far
 *       enough that they were never sent this session) are decoded from the store and handed to the
 *       normal chunk intake, a few per tick. The renderer treats them exactly like server chunks:
 *       real blocks, real light, correct depth and fog.</li>
 * </ol>
 *
 * <p><b>What it cannot do:</b> the renderer only meshes chunks within the video-settings render
 * distance, so that setting is the visibility ceiling no matter how large the radius here is. And
 * remembered terrain is a snapshot - a block someone changed since the capture stays wrong until
 * you walk close enough for the server to send the chunk again (which overwrites the memory).
 *
 * <p>Only whole-island lobbies are supported ({@link #ISLANDS}), plus the Garden as the one
 * player-shaped exception; instanced places (dungeons, private islands) differ per visit and would
 * replay someone else's layout. Every island keeps its <b>own</b> map file - see
 * {@link #SEPARATE_MAPS} for why no two islands may share one.
 *
 * <p><b>Threads.</b> Everything except the IO worker runs on the client thread: capture is called
 * from the packet handler (main thread - the network thread's pass never reaches the handler body),
 * {@link #tick} from the client tick, the forget veto answers only on the main-thread pass. The
 * single worker thread does the disk reads/writes and the gzip work.
 */
public final class FarTerrainManager {

    /**
     * Every supported island, each with a terrain file of its own. No two islands may share one:
     * every grouping ever tried ended the same way, with the claim record proving the members
     * claim the same near-origin region and overwrite each other in the shared file.
     *
     * <p>The last such grouping was "Main Map" (Hub, Crimson Isle, Gold Mine, the Farming
     * Islands), assumed to be one disjoint coordinate space until the full-rescan claim record
     * (2026-08-06) showed all four claiming the origin region - six overlapping pairs, 806
     * multi-claimed positions in one file. {@link FarTerrainStore#migrateLegacyMainMap} splits
     * that file into these per-island ones on startup. How islands relate <i>geometrically</i> -
     * which neighbours are drawn at their true positions from their own stores - is a per-pair
     * measurement that lives in {@code FarTerrainNeighborView.FRAMES}, not a file-sharing
     * decision.
     *
     * <p>"Deep Caverns" is the cautionary one: it occupies the same X/Z as the surface islands
     * but is, per the player, genuinely an older generation of the map - the pre-nether-update
     * world kept around - measured at similarity 0.01 across 928 shared positions with the Hub.
     * Same coordinates, different era; it may never share a file or a frame with the present.
     *
     * <p>"The Garden" and "Private Island" are the <b>player-shaped</b> maps: everyone's occupies
     * the same coordinates, so one file cannot hold two of them. Which one it holds is decided by
     * {@link FarTerrainOwnership} - only your own is ever written to disk, someone else's is drawn
     * from a session-only store and thrown away when you leave, and while the owner is still unknown
     * nothing is opened at all.
     */
    public static final List<String> SEPARATE_MAPS = List.of(
            "Deep Caverns",
            // The islands of the shared aligned world (measured - see FarTerrainNeighborView
            // .FRAMES): own file each because the per-server renditions diverge, one frame
            // because the positions agree, chunk for chunk, at offset zero.
            "The Park",
            "Galatea",
            "Torrhus Canyon",
            "Spider's Den",
            "The End",
            "Hub",
            "Crimson Isle",
            "Gold Mine",
            "The Farming Islands",
            "The Garden",
            "Private Island",
            "Dungeon Hub",
            "Backwater Bayou",
            "Dwarven Mines",
            "Jerry's Workshop",
            // The whole Rift is one world: every area from the Wyld Woods to the Mirrorverse is
            // walked to rather than warped to, so they share one coordinate space and one file. Its
            // caves are carved into that same terrain rather than stacked under it, which is what
            // separates this from the Deep Caverns case above - a chunk column simply contains both.
            "The Rift");

    /** Every supported island, for the settings label. */
    public static final List<String> ISLANDS = SEPARATE_MAPS;

    /** Radius cap in Performance mode - the point of that mode is a bounded workload. */
    public static final int PERFORMANCE_RADIUS = 32;

    /** Hard cap of the slider. */
    public static final int MAX_RADIUS = 128;

    /**
     * The serve budget is <b>time</b>, not a chunk count. A fixed count has to be sized for the
     * weakest machine, which turned a rejoin into minutes of terrain trickling in on hardware that
     * decodes a chunk in a fraction of a millisecond. Spending a fixed slice of the tick instead
     * self-scales: a fast machine serves hundreds of chunks per tick, a slow one a handful, and
     * neither drops frames over it. The count cap is only a flood guard for the mesh builder.
     */
    private static final long BUDGET_NANOS_ON = 6_000_000L;          // 6 ms of the 50 ms tick
    private static final long BUDGET_NANOS_PERFORMANCE = 2_000_000L; // 2 ms
    private static final int BUDGET_CHUNKS_ON = 192;
    private static final int BUDGET_CHUNKS_PERFORMANCE = 24;

    /**
     * The fill budget while Uncapped still has a real backlog. At the polite 6 ms a tick a
     * 40k-chunk map takes the better part of an hour to appear, which reads as "uncapped does not
     * work" - clicking it was the player explicitly trading smoothness for the whole map, so the
     * fill is allowed a third of the tick until the backlog is gone, then the polite budget
     * resumes for stragglers.
     */
    private static final long BUDGET_NANOS_UNCAPPED_FILL = 16_000_000L; // 16 ms
    private static final int BUDGET_CHUNKS_UNCAPPED_FILL = 512;

    /** Backlog above which the uncapped fill budget applies - below it the normal pace is fine. */
    private static final int UNCAPPED_FILL_THRESHOLD = 64;

    /** How often the dirty store is rewritten to disk, in ticks (30s / 60s). */
    private static final int SAVE_TICKS_ON = 600;
    private static final int SAVE_TICKS_PERFORMANCE = 1200;

    /**
     * Chunks captured before the island is known (the login burst arrives seconds before the tab
     * list does) wait here, keyed by position. Bounded: if the island never resolves, the oldest
     * entries fall out instead of the map growing for a whole wrong-island session.
     */
    private static final int EARLY_BUFFER_LIMIT = 768;

    /** Serving stops for the session's island after this many broken chunks in a row (stale file). */
    private static final int MAX_DECODE_FAILURES = 32;

    /** How often one chunk may be re-offered to a refusing cache before the queue moves past it. */
    private static final int MAX_REJECT_RETRIES = 3;

    private static final FarTerrainManager INSTANCE = new FarTerrainManager();

    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "SBS-FarTerrain-IO");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Decompression pool - the only part of serving that can leave the client thread.
     *
     * <p>Applying a chunk is not movable: {@code handleLevelChunkWithLight} mutates the chunk cache,
     * the light engine and the block entities, all of which belong to the client thread, and the
     * mesh build it triggers is <i>already</i> parallel inside vanilla. The gunzip in front of it is
     * different - pure bytes in, bytes out, touching nothing - and it was sitting inside the serve
     * budget, so a large part of that 6 ms was being spent decompressing rather than applying.
     * Moving it here spends the tick on the work that has to be on the tick.
     */
    private final ExecutorService decompress = Executors.newFixedThreadPool(
            Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 4)),
            new java.util.concurrent.ThreadFactory() {
                private int index;

                @Override
                public Thread newThread(Runnable r) {
                    Thread thread = new Thread(r, "SBS-FarTerrain-Decompress-" + index++);
                    thread.setDaemon(true);
                    // Below the game: this is work done ahead of time, and it must never take a
                    // core away from the render or mesh threads that are drawing the frame.
                    thread.setPriority(Thread.MIN_PRIORITY);
                    return thread;
                }
            });

    /** Chunks already decompressed on the pool and waiting for their turn on the client thread. */
    private final java.util.concurrent.ConcurrentHashMap<Long, byte[]> ready =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Positions currently being decompressed, so the same one is never queued twice. */
    private final java.util.Set<Long> inFlight =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** How far ahead of the serve cursor to decompress, and the ceiling on decompressed bytes held. */
    private static final int PREFETCH_AHEAD = 96;
    private static final int READY_LIMIT = 192;

    /** How long serving stays paused after memory was handed back under pressure - 30 s. */
    private static final int PRESSURE_PAUSE_TICKS = 600;

    /** Tick until which serving is paused because the heap was tight. */
    private int pressurePauseUntil;

    /** The current island's store once its file has been read; {@code null} while off-island. */
    private volatile FarTerrainStore active;

    /** A store whose file is still being read on the worker (main thread only). */
    private FarTerrainStore pending;

    /**
     * The store that survived the last level swap (main thread only). Warping between two islands
     * of the same map replaces the whole {@link ClientLevel}, but the terrain file is the same -
     * closing and re-reading it on every hop added seconds of dead time and a window where fresh
     * captures looked lost. The store is parked across the swap and reactivated as-is when the new
     * island resolves to the same map; a save is still submitted at the swap for crash-safety.
     */
    private FarTerrainStore parked;

    /** The whitelisted island the player is on right now, or {@code null} (main thread only). */
    private String islandName;

    /** The map that island belongs to - what the open store is keyed on (main thread only). */
    private String mapName;

    /** Re-entry guard: chunks this manager itself hands to the intake must not be re-captured. */
    private boolean injecting;

    /**
     * Which island captured each position, for this session and this map only - the collision
     * detector. Two islands grouped onto one map must occupy disjoint X/Z; when they do not, they
     * silently overwrite each other's terrain and the only symptom is the wrong island appearing in
     * the distance. This caught Deep Caverns sitting under the surface islands, and it exists so the
     * next such pairing announces itself in the log instead of being noticed by eye.
     */
    private FarTerrainClaims claims;

    private final Map<Long, byte[]> earlyBuffer = new LinkedHashMap<>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, byte[]> eldest) {
            return size() > EARLY_BUFFER_LIMIT;
        }
    };

    /**
     * The chunks still to serve, nearest first, as a primitive array walked by {@link #serveIndex} -
     * a boxed queue re-sorted on every rebuild costs milliseconds at a 128-chunk radius, this costs
     * microseconds.
     */
    private long[] serveOrder;
    private int serveIndex;
    private int queueCenterX = Integer.MIN_VALUE;
    private int queueCenterZ = Integer.MIN_VALUE;

    /**
     * The radius the current queue was built for. The enclosure clamp moves it without the player
     * moving at all, and a queue built for a cave-sized window would otherwise stay that size until
     * something else happened to rebuild it - which is exactly the moment of walking back outside.
     */
    private int queuedRadius = -1;

    private ClientLevel radiusLevel;
    private int appliedRadius = -1;
    private int decodeFailures;
    private int tickCounter;
    private boolean hadLevel;

    /** Chunks successfully served from the store this level - the "it works" number. */
    private int servedCount;

    /**
     * The furthest stored chunk from the player, in chunks - measured on every queue rebuild and
     * used only by the uncapped window, which sizes itself to the map instead of to a slider.
     */
    private int mapExtent;

    /**
     * Served chunks the chunk cache refused to take (its storage ring was not wide enough at that
     * moment). These are NOT failures of the stored bytes - the entry stays in the store and is
     * retried after the radius is re-asserted; the counter existing at all is what makes an
     * otherwise silent drop (vanilla just logs and returns null) visible in the status line.
     */
    private int cacheRejects;

    /** The chunk the cache last refused and how many times running - the anti-loop guard. */
    private long lastRejectKey;
    private int rejectStreak;

    /**
     * The level the tick loop last saw. A warp to another island swaps the whole {@link ClientLevel}
     * seconds BEFORE the tab list catches up - without this reset, the new island's chunks would be
     * captured into the old island's store during that gap.
     */
    private ClientLevel currentLevel;

    /** True when the location is known and it is simply not a supported island - stop buffering. */
    private boolean unsupportedIsland;

    /** The mode seen last tick, so switching to Off can be acted on rather than merely obeyed. */
    private FarTerrainMode lastMode;

    /**
     * The tick the level last changed. Island resolution waits {@value #LEVEL_SETTLE_TICKS} ticks
     * after a swap: the location sources lag a moment behind the world, and resolving during that
     * lag would attribute the new world's chunks to the island you just left. The login burst is
     * not lost - it waits in {@link #earlyBuffer}.
     */
    private int levelResetTick;

    private static final int LEVEL_SETTLE_TICKS = 40;

    private FarTerrainManager() {
        // First thing on the IO lane, before any store can load: split the retired shared
        // "Main Map" file into per-island files (no-op once done - the file is gone).
        submitIo(FarTerrainStore::migrateLegacyMainMap);
        // Quitting the game must not lose the last capture window. The save is submitted to the IO
        // worker (never run here) so the file only ever has one writer, then the worker is given a
        // moment to finish.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            FarTerrainStore store = active;
            if (store != null) {
                submitIo(store::save);
            }
            io.shutdown();
            try {
                io.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "SBS-FarTerrain-Shutdown"));
    }

    /** {@link ExecutorService#submit} that tolerates the executor already shutting down. */
    private void submitIo(Runnable task) {
        try {
            io.submit(task);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Shutdown race: the hook above is already flushing; nothing more to do.
        }
    }

    /** The IO worker, for the neighbour view's read-only store loads - one disk lane for the module. */
    void submitIoTask(Runnable task) {
        submitIo(task);
    }

    /** The serving store, for the neighbour view's layout anchor. */
    FarTerrainStore activeStore() {
        return active;
    }

    /** Whether the real island still has a meaningful serve backlog - scenery waits behind it. */
    boolean mainBacklog() {
        return serveOrder != null && serveOrder.length - serveIndex > 32;
    }

    /**
     * Hands one packet to the chunk intake under the re-entry guard and reports whether the chunk
     * actually landed. The neighbour view's serve path - kept beside the main one rather than
     * shared with it because the main path's failure ladder (decode counters, reject streaks)
     * protects a store this packet may not even come from.
     */
    boolean injectPacket(Minecraft minecraft, ClientLevel level,
                         ClientboundLevelChunkWithLightPacket packet, int x, int z) {
        try {
            injecting = true;
            minecraft.getConnection().handleLevelChunkWithLight(packet);
        } catch (Throwable t) {
            return false;
        } finally {
            injecting = false;
        }
        return level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) != null;
    }

    public static FarTerrainManager getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.FarTerrainSettings cfg() {
        return ConfigManager.getInstance().get().farTerrain;
    }

    /** The radius actually in force: the slider, capped by the mode. */
    public static int effectiveRadius() {
        int radius = Math.max(1, Math.min(MAX_RADIUS, cfg().extraChunks));
        return cfg().mode == FarTerrainMode.PERFORMANCE
                ? Math.min(radius, PERFORMANCE_RADIUS) : radius;
    }

    /**
     * The radius within which chunks are actually kept <b>alive</b> in the client level: the slider,
     * but never meaningfully past the video render distance.
     *
     * <p>This cap is what keeps the module from freezing the game. A live chunk costs on the order
     * of a hundred kilobytes of heap; the renderer cannot draw anything past the render distance
     * anyway, so keeping a whole 128-radius alive bought nothing visible while piling up gigabytes -
     * and the moment a server transfer tore the level down, freeing all of it at once stalled the
     * "Reconfiguring" screen for as long as the GC needed. Everything OUTSIDE this window lives only
     * as compressed bytes in the store and is served back the moment the window reaches it.
     */
    /**
     * The hard ceiling on how far chunks are kept <b>alive</b>, whatever the sliders say.
     *
     * <p>Vanilla's renderer allocates its section grid as {@code (2r+1)² × height} and keeps every
     * chunk in the window loaded, so the cost is square-law: r=32 is ~100k slots, r=64 ~400k, r=128
     * ~1.6M plus the live chunks to fill it. Past this point a server transfer has to tear all of it
     * down at once, which is what stalls the "Reconfiguring" screen. Remembering terrain further out
     * is free (it is compressed bytes); <i>drawing</i> it is not, and this is where drawing stops.
     */
    public static final int MAX_LIVE_RADIUS = 64;

    /**
     * Absolute guard on the uncapped window. Even "load everything" has to size a chunk-cache array
     * of {@code (2r+1)²} slots, so an unbounded value would allocate before it ever loaded anything.
     * No SkyBlock map comes near this.
     */
    public static final int UNCAPPED_MAX_RADIUS = 384;

    /**
     * The high-water mark of the uncapped window this map. {@link #mapExtent} is measured from the
     * player, so it CHANGES with every step - and every consumer of this radius (the chunk cache,
     * the renderer's distance, the section grid) treats a change as "rebuild yourself". Walking
     * across the island turned that into a full render reset every couple of seconds. The window
     * therefore only ever grows while a map is open; it snaps back on a map change.
     */
    private int windowHighWater;

    public static int liveRadius() {
        if (cfg().uncapped) {
            // Sized to what the map actually contains rather than a blind maximum: loading the whole
            // file still only needs a window big enough to hold the furthest chunk in it.
            int needed = Math.min(UNCAPPED_MAX_RADIUS,
                    Math.max(effectiveRadius(), INSTANCE.mapExtent + 2));
            INSTANCE.windowHighWater = Math.max(INSTANCE.windowHighWater, needed);
            return INSTANCE.windowHighWater;
        }
        INSTANCE.windowHighWater = 0;
        int renderDistance = Minecraft.getInstance().options.renderDistance().get();
        // The island's own window: the slider, never past what the renderer draws.
        int window = Math.min(effectiveRadius(), renderDistance + 2);
        // The scenery's window is its own, and must NOT be clamped by the render-distance slider -
        // that clamp is exactly what silently discarded it. The reach is constant per layout (box
        // to box, no player term), so widening the window with it cannot jitter, and
        // ownRenderDistance() raises the drawn distance to match.
        if (cfg().neighborView) {
            int reach = FarTerrainNeighborView.getInstance().reachChunks();
            if (reach > 0) {
                window = Math.max(window, reach + 2);
            }
        }
        return Math.min(MAX_LIVE_RADIUS, window);
    }

    /**
     * The render distance the renderer should use, ignoring the server's clamp, or {@code null} to
     * leave vanilla alone. Read by {@code RenderDistanceMixin}.
     *
     * <p>Hypixel announces a small chunk radius and vanilla clamps the effective distance to it, so
     * remembered terrain was being drawn no further than the server's own bubble no matter how large
     * the module's radius was. Terrain this client serves itself is not the server's to limit - so
     * while the module is on, the player's own slider is the answer.
     *
     * <p>The one exception is a view that is boxed in anyway - see {@link FarTerrainEnclosure}. The
     * chunks stay loaded and meshed either way; this only stops the renderer walking through terrain
     * a wall of stone hides, and the number goes straight back up when the wall does not.
     */
    public static Integer ownRenderDistance() {
        if (cfg().mode == FarTerrainMode.OFF) {
            return null;
        }
        // Uncapped is an OVERRIDE, not another input. It means "the whole map, drawn" - so the
        // slider stops being a ceiling, and neither the enclosure clamp nor the framerate
        // controller may take any of it back. Both of those exist to spend less than the player
        // asked for; this is the player asking for all of it, explicitly, having been warned.
        if (cfg().uncapped) {
            return Math.max(Minecraft.getInstance().options.renderDistance().get(), liveRadius());
        }
        int base = Minecraft.getInstance().options.renderDistance().get();
        // The neighbour scenery has to be REACHABLE by the renderer or the whole feature reads as
        // broken: with the slider at 32 the laid-out islands sat 30-60 chunks out - served,
        // cached, and invisible. reachChunks is constant per layout, so this does not jitter; the
        // live-radius cap keeps it inside the window everything else already respects.
        if (cfg().neighborView) {
            int reach = FarTerrainNeighborView.getInstance().reachChunks();
            if (reach > 0) {
                base = Math.max(base, Math.min(MAX_LIVE_RADIUS, reach + 2));
            }
        }
        Integer clamp = drawChunks();
        return clamp == null ? base : Math.min(base, clamp);
    }

    /**
     * The narrowest of the two things allowed to take drawn distance away, or {@code null} when
     * neither is. They answer different questions - {@link FarTerrainEnclosure} "can this even be
     * seen from here", {@link FarTerrainAutoDistance} "is this costing more than the player agreed
     * to spend" - and both are honoured by taking whichever is stricter.
     */
    private static Integer drawChunks() {
        Integer enclosed = FarTerrainEnclosure.viewChunks();
        Integer auto = FarTerrainAutoDistance.viewChunks();
        if (enclosed == null) {
            return auto;
        }
        return auto == null ? enclosed : Math.min(enclosed, auto);
    }

    /**
     * The chunk radius the server itself announced, or {@code 0} before it has said. This is the
     * "what vanilla would do here" number, and it is the floor under every narrowing this module
     * does to itself - dropping below it would make the module worse than not having it.
     */
    public static int serverRadius() {
        var connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            return 0;
        }
        return ((sbs.modid.client.core.mixin.ClientPacketListenerAccessor) connection)
                .skyblockSimplified$serverChunkRadius();
    }

    /**
     * The radius remembered chunks are decoded and handed to the renderer for. Normally the live
     * window; while the view is enclosed it collapses to what can actually be seen, because
     * decoding terrain that sits behind solid rock is the one cost with nothing at all to show for
     * it. Nothing is unloaded by this - {@link #liveRadius()} is untouched, so everything already
     * served stays in memory and the full view returns the moment the measurement opens up.
     *
     * <p>Uncapped mode is left alone: "load the whole map" is an explicit choice, and it has already
     * paid for what it holds.
     */
    private static int serveRadius() {
        int radius = liveRadius();
        Integer clamp = drawChunks();
        if (clamp == null || cfg().uncapped) {
            return radius;
        }
        // A margin past what is drawn, so turning to face a new direction finds it already there.
        return Math.min(radius, clamp + 4);
    }

    /**
     * The chunk-cache radius the client should use - called by the mixin on every
     * {@code updateViewRadius}. The cache must be at least as wide as the live window or the
     * intake silently drops everything beyond it; when the module is off the server's value passes
     * through untouched. Sized to {@link #liveRadius()}, not the slider - nothing beyond the live
     * window is ever put into the cache.
     */
    public static int inflateRadius(int original) {
        return cfg().mode == FarTerrainMode.OFF ? original
                : Math.max(original, liveRadius() + 2);
    }

    // ------------------------------------------------------------------ capture

    /**
     * Called at the tail of the chunk-packet handler (main thread): remembers the chunk if a
     * supported island is active. The re-encode costs on the order of a tenth of a millisecond;
     * compression and disk are the worker's problem.
     */
    public void onChunkFromServer(ClientboundLevelChunkWithLightPacket packet) {
        if (injecting || cfg().mode == FarTerrainMode.OFF) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || level != currentLevel || !minecraft.packetProcessor().isSameThread()) {
            return;   // level != currentLevel: a world swap the tick loop has not processed yet
        }
        FarTerrainStore store = active;
        if (store != null) {
            byte[] raw = encode(level, packet);
            int x = packet.getX();
            int z = packet.getZ();
            noteCapture(x, z);
            submitIo(() -> store.put(x, z, raw));
            return;
        }
        if (!unsupportedIsland) {
            // Island not resolved / file still reading (the login burst arrives seconds before the
            // tab list does): hold the bytes, tick() flushes them into the store or drops them.
            earlyBuffer.put(FarTerrainStore.key(packet.getX(), packet.getZ()),
                    encode(level, packet));
        }
    }

    /**
     * Records which island captured a position and warns when a second island claims the same one -
     * i.e. when two islands sharing a map file are not in disjoint X/Z after all, and are therefore
     * overwriting each other. The fix a warning calls for is always the same: move one of the two
     * islands into {@link #SEPARATE_MAPS} and delete the shared map's file once.
     */
    private void noteCapture(int x, int z) {
        FarTerrainClaims record = claims;
        if (record != null && islandName != null) {
            record.claim(islandName, x, z);
        }
    }

    /** The current map's island-claim record, for the settings report. */
    public FarTerrainClaims claims() {
        return claims;
    }

    /** The resolved island underfoot, or {@code null} - the neighbour layout anchors on its box. */
    String currentIsland() {
        return islandName;
    }

    private static byte[] encode(ClientLevel level, ClientboundLevelChunkWithLightPacket packet) {
        RegistryFriendlyByteBuf buf =
                new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        try {
            ClientboundLevelChunkWithLightPacket.STREAM_CODEC.encode(buf, packet);
            byte[] raw = new byte[buf.readableBytes()];
            buf.readBytes(raw);
            return raw;
        } finally {
            buf.release();
        }
    }

    // ------------------------------------------------------------------ keep

    /**
     * Whether a server-ordered chunk forget should be vetoed (the chunk stays loaded). Answered
     * only on the handler's main-thread pass - the network-thread pass just schedules and its
     * cancel state is irrelevant; deciding there would race the location reads.
     */
    public boolean keepChunk(int chunkX, int chunkZ) {
        if (cfg().mode == FarTerrainMode.OFF) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null
                || !minecraft.packetProcessor().isSameThread()
                || islandName == null) {
            return false;
        }
        int radius = liveRadius();
        return Math.abs(chunkX - minecraft.player.chunkPosition().x()) <= radius
                && Math.abs(chunkZ - minecraft.player.chunkPosition().z()) <= radius;
    }

    // ------------------------------------------------------------------ tick

    /** Driven once per client tick from the shared tick fan-out. */
    public void tick(Minecraft minecraft) {
        // Before the level check on purpose. Minecraft parses options.txt in its own constructor and
        // replaces any render distance past the stock maximum with the option's DEFAULT, so the
        // widened slider and this mod's saved value have to be back in place while the player is
        // still at the title screen - by the time a world exists, the renderer has already been
        // built at the wrong size.
        FarTerrainRenderDistance.sync(cfg().mode != FarTerrainMode.OFF);
        ClientLevel level = minecraft.level;
        if (level == null) {
            if (hadLevel) {
                leaveWorld();
            }
            return;
        }
        if (level != currentLevel) {
            resetForNewLevel();
            currentLevel = level;
        }
        hadLevel = true;
        tickCounter++;
        // Before the mode is read: the toggle key has to work in every mode, above all in Off - it
        // is the way back on.
        FarTerrainToggle.tick(minecraft);
        // The mark key works in every mode too - real terrain can be marked with the module off.
        FarTerrainAlign.tick(minecraft);
        FarTerrainMode mode = cfg().mode;
        if (mode == FarTerrainMode.OFF) {
            // Off has to mean off NOW, not at the next world change: give the terrain back on the
            // transition rather than merely stopping to add more. Everything below is skipped too -
            // island resolution included, or the store would simply be reopened on the next tick.
            if (lastMode != null && lastMode != FarTerrainMode.OFF) {
                disableNow(minecraft, level);
            }
            lastMode = mode;
            FarTerrainEnclosure.reset();
            FarTerrainAutoDistance.reset();
            return;
        }
        lastMode = mode;
        FarTerrainEnclosure.tick(minecraft);
        FarTerrainAutoDistance.tick(minecraft);

        if (tickCounter - levelResetTick >= LEVEL_SETTLE_TICKS
                && (tickCounter % 10 == 0 || (active == null && pending == null))) {
            updateIsland();
        }
        promotePending();

        if (minecraft.player == null) {
            return;
        }
        ensureCacheRadius(minecraft, level);
        // The chunk cache being wide enough is only half of it - the RENDERER's section grid is
        // allocated at a fixed size too, and it is the one that silently drops everything past it.
        if (tickCounter % 20 == 0) {
            FarTerrainViewArea.ensureFits(minecraft);
        }

        FarTerrainStore store = active;
        if (store == null) {
            return;
        }
        serve(minecraft, level, store);
        if (tickCounter % 100 == 0) {
            dropFarChunks(minecraft, level, store);
        }
        if (cfg().neighborView && mapName != null) {
            FarTerrainNeighborView.getInstance().tick(minecraft, this, mapName, level);
        }

        int saveEvery = cfg().mode == FarTerrainMode.ON ? SAVE_TICKS_ON : SAVE_TICKS_PERFORMANCE;
        if (tickCounter % saveEvery == 0) {
            submitIo(store::save);
            FarTerrainClaims record = claims;
            if (record != null) {
                submitIo(record::save);
            }
        }
    }

    /**
     * The other half of the sliding window: chunks the player has moved away from go back to being
     * compressed bytes. Serving fills the window in; without this, everything ever kept or served
     * stayed alive, so a long session ended up holding a whole island as live chunks - gigabytes of
     * heap for terrain the renderer could not draw anyway, and a level teardown (server switch)
     * that stalled on freeing it all at once.
     *
     * <p>Only positions the store knows are touched, which is exactly the set this module could
     * have kept alive: every kept chunk was captured on arrival. The margin over
     * {@link #liveRadius()} is hysteresis so the border does not flap; genuine server-bubble chunks
     * sit far inside the window and can never be hit.
     */
    private void dropFarChunks(Minecraft minecraft, ClientLevel level, FarTerrainStore store) {
        if (cfg().uncapped) {
            return;   // holding the whole map open is the point; evicting would undo the loading
        }
        dropBeyond(minecraft, level, store, liveRadius() + 4);
    }

    /**
     * Unloads every chunk this module is holding open past {@code limit} chunks from the player.
     *
     * <p>Only positions the store knows are touched, which is exactly the set this module could have
     * kept alive: every kept chunk was captured on arrival. The caller's limit must never go below
     * the server's own radius - inside that bubble the chunks are Hypixel's, it will not send them
     * again, and dropping one leaves a hole in the world that nothing fills.
     */
    private void dropBeyond(Minecraft minecraft, ClientLevel level, FarTerrainStore store,
                            int limit) {
        int centerX = minecraft.player.chunkPosition().x();
        int centerZ = minecraft.player.chunkPosition().z();
        for (long key : store.keys()) {
            int x = FarTerrainStore.keyX(key);
            int z = FarTerrainStore.keyZ(key);
            if (Math.abs(x - centerX) <= limit && Math.abs(z - centerZ) <= limit) {
                continue;
            }
            if (level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) != null) {
                level.getChunkSource().drop(new net.minecraft.world.level.ChunkPos(x, z));
            }
        }
    }

    /**
     * Hands everything back the moment the module is switched off, without waiting for a world
     * change: the remembered chunks are unloaded down to the server's own bubble, the widened chunk
     * cache is given back at the server's radius, and the store is closed.
     *
     * <p>Without this, "off" only meant "stop adding more" - the terrain already served stayed
     * loaded and drawn, the memory stayed taken, and the only way to actually be rid of it was to
     * warp somewhere. A master switch that needs a world change to take effect is not a master
     * switch.
     */
    private void disableNow(Minecraft minecraft, ClientLevel level) {
        FarTerrainNeighborView.getInstance().releaseAll(level);
        FarTerrainStore store = active;
        if (store != null) {
            submitIo(store::save);   // whatever was captured up to now is still worth keeping
        }
        serveOrder = null;
        serveIndex = 0;
        queuedRadius = -1;
        clearPrefetch();
        int server = serverRadius();
        if (store != null && minecraft.player != null && server > 0) {
            dropBeyond(minecraft, level, store, server);
            level.getChunkSource().updateViewRadius(server);
            sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Terrain] module switched off - remembered chunks unloaded, "
                            + "chunk cache handed back at radius {}", server);
        }
        radiusLevel = null;
        appliedRadius = -1;
        // The island has to read as unresolved again, or switching the module back on would find
        // the map already current and never reopen the store.
        active = null;
        pending = null;
        parked = null;
        mapName = null;
        islandName = null;
        earlyBuffer.clear();
    }

    /**
     * Whether two islands share a coordinate space, i.e. a position on one is meaningful while
     * standing on the other. True only for islands of the shared map; two islands with their own
     * maps are never comparable even if both are supported, and an unsupported island shares a
     * coordinate space with nothing as far as this mod knows.
     *
     * <p>This is what lets a marker on a Park NPC sit correctly on the horizon while you stand in
     * the Hub - the coordinates genuinely line up, which is the same fact that lets one terrain file
     * serve both.
     */
    public static boolean sameMap(String islandA, String islandB) {
        if (islandA == null || islandB == null) {
            return false;
        }
        if (islandA.equalsIgnoreCase(islandB)) {
            return true;
        }
        String a = mapOf(islandA);
        return a != null && a.equals(mapOf(islandB));
    }

    /** Whether remembered terrain is being served at all right now. */
    public static boolean active() {
        return cfg().mode != FarTerrainMode.OFF;
    }

    /** Whether the player is on a garden or private island, i.e. somewhere ownership matters. */
    public static boolean onPlayerShapedMap() {
        return FarTerrainOwnership.appliesTo(INSTANCE.islandName);
    }

    /** The map an island's terrain belongs to, or {@code null} when it is not supported. */
    private static String mapOf(String island) {
        // One island, one map - the identity is the whole point now (see SEPARATE_MAPS).
        return canonicalIsland(island);
    }

    /**
     * The whitelist's own spelling of an island name, or {@code null} when no entry names it.
     *
     * <p>Matching is forgiving about two things the sources get wrong: capitalisation (the NPC
     * catalogue's island spellings are typed by hand) and a leading "The" (the tab list serves
     * "Garden" where this list says "The Garden" - and an entry that never matches does not just
     * disable a feature here, it silently demotes the island to session-only terrain that is thrown
     * away on the next hop, which is how a listed island came to be reported as "not getting
     * saved"). The <b>returned</b> name is always the list's spelling, so the store file keeps one
     * name however the island was written or announced.
     */
    private static String canonicalIsland(String island) {
        if (island == null || island.isBlank()) {
            return null;
        }
        String want = normalize(island);
        for (String own : SEPARATE_MAPS) {
            if (normalize(own).equals(want)) {
                return own;
            }
        }
        return null;
    }

    /** Lower-cased, trimmed, and without a leading "the " - the comparison form of an island name. */
    private static String normalize(String name) {
        String out = name.trim().toLowerCase(java.util.Locale.ROOT);
        return out.startsWith("the ") ? out.substring(4) : out;
    }

    /**
     * Resolves the current island and swaps stores when its <b>map</b> changes. Walking from the Hub
     * to the Park changes the island but not the map, so the store stays open and everything already
     * queued or remembered keeps working across the border.
     */
    private void updateIsland() {
        // The live name is canonicalised FIRST: the tab list and the whitelist do not always agree
        // on spelling ("Garden" vs "The Garden"), and a listed island that misses here does not
        // fail loudly - it falls through to the ephemeral path below and its terrain silently stops
        // being saved. The onIsland loop stays as the fallback because it can also resolve through
        // zone names when the tab line is lagging or absent.
        String live = SkyBlockLocation.island();
        String now = canonicalIsland(live);
        if (now == null) {
            for (String island : ISLANDS) {
                if (SkyBlockLocation.onIsland(island)) {
                    now = island;
                    break;
                }
            }
        }
        // Anywhere the whitelist does not name still gets far terrain, keyed by whatever the island
        // is called - it just lives in memory for the session (see FarTerrainStore#ephemeral). Only
        // a place with no readable name at all is skipped, since there would be nothing to key on.
        boolean ephemeral = false;
        if (now == null && !live.isEmpty()) {
            now = live;
            ephemeral = true;
        }
        unsupportedIsland = now == null;
        islandName = now;

        String nextMap = ephemeral ? now : mapOf(now);
        // Player-shaped maps: everyone's garden occupies the same coordinates, so whose it is
        // decides which file - if any - this is allowed to touch. See FarTerrainOwnership.
        if (nextMap != null && !ephemeral && FarTerrainOwnership.appliesTo(now)) {
            switch (FarTerrainOwnership.state()) {
                case VISITING -> {
                    // Drawn while you are here, gone the moment you leave: an ephemeral store has
                    // no file behind it and is never parked across a level swap.
                    nextMap = now + " (visiting)";
                    ephemeral = true;
                }
                case UNKNOWN -> nextMap = null;   // open nothing until the owner is established
                default -> { }
            }
        }
        if (java.util.Objects.equals(nextMap, mapName)) {
            if (unsupportedIsland) {
                earlyBuffer.clear();   // known and unsupported - stop holding the login burst
            }
            return;
        }
        mapName = nextMap;
        FarTerrainStore old = active;
        active = null;
        pending = null;
        serveOrder = null;
        serveIndex = 0;
        queueCenterX = Integer.MIN_VALUE;
        decodeFailures = 0;
        // The previous map's extent says nothing about this one, and it now sizes the uncapped
        // render distance - stale, it would briefly draw (and allocate) the old map's reach here.
        mapExtent = 0;
        windowHighWater = 0;
        FarTerrainClaims oldClaims = claims;
        if (oldClaims != null) {
            submitIo(oldClaims::save);
        }
        // One claim record per map, matching the terrain file it explains.
        claims = nextMap == null ? null : new FarTerrainClaims(nextMap);
        if (old != null) {
            submitIo(old::save);
        }
        if (nextMap == null) {
            earlyBuffer.clear();
            return;
        }
        // The same map's store survived the level swap: reuse it as-is instead of re-reading the
        // file - the terrain is on screen the moment the island resolves, and captures from just
        // before the warp are still in it even if the crash-safety save has not finished yet.
        // Never for an ephemeral map: "gone after one server hop" is the whole contract there, and
        // reusing it would replay one unknown lobby's terrain in the next.
        if (parked != null && !parked.ephemeral() && !ephemeral && parked.map().equals(nextMap)) {
            activate(parked);
            parked = null;
            return;
        }
        parked = null;
        FarTerrainStore next = new FarTerrainStore(nextMap, ephemeral);
        pending = next;
        submitIo(next::load);
    }

    /**
     * Removes chunks that belong to islands no longer grouped onto this map.
     *
     * <p>When an island is split off (the Park, Galatea), the shared file it used to write into
     * still holds its chunks at the positions where they collided - and keeps serving them, which
     * is what put floating Park fragments over the Hub. The claim record knows exactly which
     * positions each island wrote, so those entries are dropped automatically instead of asking the
     * player to delete the whole map's terrain and lose the honest chunks with it.
     */
    private void purgeForeignChunks(FarTerrainStore store) {
        FarTerrainClaims record = claims;
        if (record == null || store.ephemeral() || mapName == null) {
            return;
        }
        submitIo(() -> {
            int purged = 0;
            for (String island : record.islandsBySize()) {
                if (mapName.equals(mapOf(island))) {
                    continue;
                }
                for (long key : record.keysOf(island)) {
                    store.remove(key);
                    purged++;
                }
                record.removeIsland(island);
                sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                        "[SBS][Terrain] '{}' no longer belongs to the '{}' map - its {} recorded "
                                + "chunks purged from the shared file", island, mapName, purged);
            }
            if (purged > 0) {
                store.save();
                record.save();
            }
        });
    }

    /** Promotes a finished load to active and flushes the login-burst buffer into it. */
    private void promotePending() {
        if (pending == null || !pending.loaded()) {
            return;
        }
        FarTerrainStore store = pending;
        pending = null;
        activate(store);
    }

    /** Makes a store the serving one and flushes the login-burst buffer into it. */
    private void activate(FarTerrainStore store) {
        active = store;
        purgeForeignChunks(store);
        sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Terrain] serving {} - {} chunks remembered, {} captured during login",
                store.map(), store.size(), earlyBuffer.size());
        for (Map.Entry<Long, byte[]> entry : earlyBuffer.entrySet()) {
            long key = entry.getKey();
            byte[] raw = entry.getValue();
            submitIo(() -> store.put(FarTerrainStore.keyX(key), FarTerrainStore.keyZ(key), raw));
        }
        earlyBuffer.clear();
    }

    /**
     * Keeps the client chunk cache wide enough for the serve radius. {@code updateViewRadius}
     * rebuilds the cache storage, so it is only called when the level or the wanted radius actually
     * changed; the passed value is the server's own radius - the mixin inflates it, and using the
     * real server value means switching the module off later can never shrink the cache below what
     * vanilla needs.
     */
    private void ensureCacheRadius(Minecraft minecraft, ClientLevel level) {
        int radius = liveRadius();   // also re-fires when the video render distance is changed
        if (level == radiusLevel && radius == appliedRadius) {
            return;
        }
        var connection = minecraft.getConnection();
        if (connection == null) {
            return;
        }
        int server = ((sbs.modid.client.core.mixin.ClientPacketListenerAccessor) connection)
                .skyblockSimplified$serverChunkRadius();
        if (server <= 0) {
            return;   // not told yet - retry next tick
        }
        level.getChunkSource().updateViewRadius(server);
        radiusLevel = level;
        appliedRadius = radius;
    }

    /** Hands remembered chunks to the normal chunk intake, spending a bounded slice of the tick. */
    private void serve(Minecraft minecraft, ClientLevel level, FarTerrainStore store) {
        if (decodeFailures >= MAX_DECODE_FAILURES) {
            return;   // this map's file predates a game update; serving more would just spam
        }
        if (tickCounter < pressurePauseUntil) {
            return;   // memory was just handed back; refilling immediately would undo it
        }
        var player = minecraft.player;
        int centerX = player.chunkPosition().x();
        int centerZ = player.chunkPosition().z();
        // Hysteresis of 2 chunks: rebuilding (and re-sorting) on every border cross while running
        // would churn for no visible ordering difference. The periodic rebuild picks up chunks
        // captured since, once the current order is drained.
        boolean exhausted = serveOrder == null || serveIndex >= serveOrder.length;
        if (serveOrder == null || serveRadius() != queuedRadius
                || Math.abs(centerX - queueCenterX) >= 2 || Math.abs(centerZ - queueCenterZ) >= 2
                || (exhausted && tickCounter % 40 == 0)) {
            rebuildQueue(level, store, centerX, centerZ);
        }

        prefetch(store);

        boolean fast = cfg().mode == FarTerrainMode.ON;
        long nanos = fast ? BUDGET_NANOS_ON : BUDGET_NANOS_PERFORMANCE;
        int budget = fast ? BUDGET_CHUNKS_ON : BUDGET_CHUNKS_PERFORMANCE;
        if (cfg().uncapped && serveOrder.length - serveIndex > UNCAPPED_FILL_THRESHOLD) {
            nanos = BUDGET_NANOS_UNCAPPED_FILL;
            budget = BUDGET_CHUNKS_UNCAPPED_FILL;
        }
        long deadline = System.nanoTime() + nanos;
        while (budget > 0 && serveIndex < serveOrder.length && System.nanoTime() < deadline) {
            long key = serveOrder[serveIndex++];
            int x = FarTerrainStore.keyX(key);
            int z = FarTerrainStore.keyZ(key);
            if (level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) != null) {
                // Real terrain outranks scenery: if what occupies the position is a translated
                // neighbour chunk, take the spot back and serve the real one over it.
                if (!FarTerrainNeighborView.reclaim(level, x, z)) {
                    continue;   // arrived from the server in the meantime
                }
            }
            // Decompressed ahead of time on the pool where possible; the synchronous fallback only
            // runs when the cursor has caught up with the prefetch, which is the first tick after a
            // rebuild and then effectively never.
            byte[] raw = ready.remove(key);
            if (raw == null) {
                raw = store.get(key);
            }
            if (raw == null) {
                continue;
            }
            budget--;

            // Decode and apply are handled separately on purpose: a decode error means the STORED
            // BYTES are bad (another game version) and the entry is dropped, while an apply problem
            // means the CLIENT was not ready for them and the entry must survive for a retry.
            ClientboundLevelChunkWithLightPacket packet;
            try {
                packet = ClientboundLevelChunkWithLightPacket.STREAM_CODEC.decode(
                        new RegistryFriendlyByteBuf(
                                Unpooled.wrappedBuffer(raw), level.registryAccess()));
            } catch (Throwable t) {
                store.remove(key);
                decodeFailures++;
                if (decodeFailures <= 3) {
                    sbs.modid.SkyblockSimplifiedSBS.LOGGER.warn(
                            "[SBS][Terrain] stored chunk {},{} no longer decodes ({}) - dropped",
                            x, z, t.toString());
                }
                continue;
            }
            // Applying is where a stored chunk is really parsed - the decode above only copies the
            // bytes out, so a palette or registry id the current session does not have ("No value
            // with id 72") surfaces HERE, not there. That makes an apply throw a statement about the
            // BYTES, not about the client being busy: retrying it can never succeed. It used to be
            // treated as retryable, which pinned the serve loop on the first such chunk forever and
            // stopped every chunk behind it in the queue from ever being served.
            boolean applyFailed = false;
            try {
                injecting = true;
                minecraft.getConnection().handleLevelChunkWithLight(packet);
            } catch (Throwable t) {
                applyFailed = true;
                store.remove(key);
                decodeFailures++;
                if (decodeFailures <= 3) {
                    sbs.modid.SkyblockSimplifiedSBS.LOGGER.warn(
                            "[SBS][Terrain] stored chunk {},{} cannot be applied ({}) - dropped. If "
                                    + "this repeats, the map's file predates a Hypixel or game "
                                    + "update: use Delete This Map's Terrain once.",
                            x, z, t.toString());
                }
            } finally {
                injecting = false;
            }
            if (applyFailed) {
                continue;   // never the retry path below - the entry is gone and the queue moves on
            }

            // Verify the chunk actually LANDED: the cache silently discards anything outside its
            // storage ring, and treating that as success is exactly the "nothing loads after a
            // server switch and it all has to be rescanned" failure. On a refusal the pass stops
            // and the radius is re-asserted next tick; the queue keeps the rest for the retry.
            if (level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) == null) {
                // A chunk the cache can NEVER hold from here (beyond the hard window cap) must be
                // skipped, not retried - the retry path below re-widens and waits, and waiting on a
                // chunk no widening can reach pins the serve loop on it for the rest of the session.
                // It stays in the store; walking toward it brings it inside a future window.
                if (Math.max(Math.abs(x - centerX), Math.abs(z - centerZ)) > UNCAPPED_MAX_RADIUS) {
                    continue;
                }
                // Re-widening is a one-shot recovery, so it is worth a few attempts and no more.
                // Any retry that keeps the cursor still is a potential infinite loop, and a loop
                // here costs the whole rest of the queue - so the chunk is given up on and the fill
                // carries on past it. It stays in the store and is tried again on the next rebuild.
                if (key == lastRejectKey && ++rejectStreak > MAX_REJECT_RETRIES) {
                    rejectStreak = 0;
                    lastRejectKey = 0L;
                    continue;
                }
                if (key != lastRejectKey) {
                    lastRejectKey = key;
                    rejectStreak = 1;
                }
                cacheRejects++;
                serveIndex--;         // retry this same chunk after the radius is re-asserted
                appliedRadius = -1;   // force ensureCacheRadius to run again next tick
                if (Integer.bitCount(cacheRejects) == 1) {   // 1, 2, 4, 8... - logs without spam
                    sbs.modid.SkyblockSimplifiedSBS.LOGGER.warn(
                            "[SBS][Terrain] chunk cache refused served chunk {},{} "
                                    + "({} refused so far) - re-widening and retrying", x, z, cacheRejects);
                }
                break;
            }
            servedCount++;
            decodeFailures = 0;
        }
    }

    /**
     * Hands the next stretch of the serve order to the decompression pool, so by the time the client
     * thread reaches those positions the bytes are already waiting.
     *
     * <p>Bounded twice over: how far ahead of the cursor it looks, and how many decompressed chunks
     * may be held at once. Decompressed packet bytes are an order of magnitude larger than the
     * compressed ones, and a prefetch that ran ahead without a ceiling would trade the tick time it
     * saves for heap - the wrong way round for a module whose whole problem is memory.
     */
    private void prefetch(FarTerrainStore store) {
        if (serveOrder == null) {
            return;
        }
        int queued = 0;
        for (int i = serveIndex; i < serveOrder.length && queued < PREFETCH_AHEAD; i++) {
            if (ready.size() + inFlight.size() >= READY_LIMIT) {
                return;
            }
            long key = serveOrder[i];
            if (ready.containsKey(key) || !inFlight.add(key)) {
                continue;
            }
            queued++;
            decompress.execute(() -> {
                try {
                    byte[] raw = store.get(key);
                    if (raw != null) {
                        ready.put(key, raw);
                    }
                } catch (Throwable ignored) {
                    // Bad stored bytes are the apply path's business - it knows how to drop the
                    // entry and count the failure. Here they are simply not prefetched.
                } finally {
                    inFlight.remove(key);
                }
            });
        }
    }

    /** Throws away prefetched bytes; called wherever the serve order stops meaning anything. */
    private void clearPrefetch() {
        ready.clear();
        inFlight.clear();
    }

    /**
     * Gives back what this module is holding when the client is short of heap.
     *
     * <p>Far terrain is usually the largest single thing this mod owns and the cheapest to rebuild -
     * every byte of it is either on disk or re-servable from the compressed store - so under real
     * pressure it should be the first to go, not the last. Soft relief drops only the prefetch;
     * {@code hard} additionally unloads the remembered chunks back to the server's own bubble.
     *
     * <p>The pause afterwards is the part that matters: without it the serve loop would begin
     * refilling on the very next tick and the guard would spend the session fighting this module
     * instead of helping it.
     */
    public void releaseUnderPressure(boolean hard) {
        clearPrefetch();
        if (!hard) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        FarTerrainStore store = active;
        int server = serverRadius();
        if (store == null || level == null || minecraft.player == null || server <= 0) {
            return;
        }
        submitIo(store::save);
        dropBeyond(minecraft, level, store, server);
        serveOrder = null;
        serveIndex = 0;
        pressurePauseUntil = tickCounter + PRESSURE_PAUSE_TICKS;
    }

    /**
     * All stored-but-missing chunks within the radius, nearest first. Distance and index are packed
     * into one primitive long each ({@code dist² << 32 | index}) so ordering 60k+ candidates is a
     * flat array sort - no boxing, no comparator - and stays invisible next to a 50 ms tick.
     */
    private void rebuildQueue(ClientLevel level, FarTerrainStore store, int centerX, int centerZ) {
        queueCenterX = centerX;
        queueCenterZ = centerZ;
        clearPrefetch();   // the old order's lookahead says nothing about the new one
        boolean uncapped = cfg().uncapped;
        int radius = serveRadius();
        queuedRadius = radius;
        long[] keys = store.keys();
        long[] packed = new long[keys.length];
        int count = 0;
        int extent = 0;
        for (int i = 0; i < keys.length; i++) {
            long dx = FarTerrainStore.keyX(keys[i]) - centerX;
            long dz = FarTerrainStore.keyZ(keys[i]) - centerZ;
            extent = Math.max(extent, (int) Math.max(Math.abs(dx), Math.abs(dz)));
            // Uncapped queues the whole file; the extent measured here is what sizes its window.
            if (!uncapped && (Math.abs(dx) > radius || Math.abs(dz) > radius)) {
                continue;
            }
            if (level.getChunkSource().getChunk(
                    FarTerrainStore.keyX(keys[i]), FarTerrainStore.keyZ(keys[i]),
                    ChunkStatus.FULL, false) != null) {
                continue;
            }
            packed[count++] = ((dx * dx + dz * dz) << 32) | i;
        }
        java.util.Arrays.sort(packed, 0, count);
        long[] order = new long[count];
        for (int i = 0; i < count; i++) {
            order[i] = keys[(int) packed[i]];
        }
        serveOrder = order;
        serveIndex = 0;
        // The neighbour scenery is part of how far there is to see - without folding it in, the
        // uncapped window (and so the drawn distance) would stop at the real island's edge and the
        // laid-out neighbours would sit just past the horizon.
        if (cfg().neighborView) {
            extent = Math.max(extent,
                    FarTerrainNeighborView.getInstance().extentFrom(centerX, centerZ));
        }
        mapExtent = Math.min(UNCAPPED_MAX_RADIUS, extent);
    }

    private void leaveWorld() {
        resetForNewLevel();
        hadLevel = false;
        currentLevel = null;
        FarTerrainClaims record = claims;
        if (record != null) {
            submitIo(record::save);
        }
    }

    /**
     * Called the moment the server announces a transfer, before the level is torn down.
     *
     * <p>The teardown frees the whole client level in one go, so whatever this module was holding
     * open is freed with it - and an inflated chunk cache full of served terrain is a great deal
     * more than vanilla ever has. That is what leaves the "Reconfiguring" screen sitting there.
     * Handing the cache back at the server's own radius first turns the extra terrain into one
     * discarded array while the game is still running, so the transfer tears down a vanilla-sized
     * level and completes at vanilla speed.
     */
    public void onServerTransfer() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        // Bookkeeping only - this method runs on the NETWORK thread (handleConfigurationStart), and
        // dropping chunks from here races the client thread; that race was a crash on every travel.
        // The scenery chunks themselves die with the level teardown moments later, and the
        // updateViewRadius below already shrinks the ring they were held in.
        FarTerrainNeighborView.getInstance().onLevelGone();
        var connection = minecraft.getConnection();
        FarTerrainStore store = active;
        if (store != null) {
            submitIo(store::save);   // the capture window up to this moment must not be lost
        }
        serveOrder = null;
        serveIndex = 0;
        if (level != null && connection != null) {
            int server = ((sbs.modid.client.core.mixin.ClientPacketListenerAccessor) connection)
                    .skyblockSimplified$serverChunkRadius();
            if (server > 0) {
                appliedRadius = -1;
                radiusLevel = null;
                level.getChunkSource().updateViewRadius(server);
                sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                        "[SBS][Terrain] transfer - chunk cache handed back at radius {} before teardown",
                        server);
            }
        }
    }

    /**
     * Drops every per-level assumption; the island re-resolves from scratch. The open store is
     * saved for crash-safety but <b>parked</b>, not closed - if the new level turns out to be the
     * same map (an island hop, or a lobby swap of the same island), it is reactivated with all its
     * chunks instead of being re-read from disk.
     */
    private void resetForNewLevel() {
        levelResetTick = tickCounter;
        islandName = null;
        mapName = null;
        unsupportedIsland = false;
        pending = null;
        serveOrder = null;
        serveIndex = 0;
        queuedRadius = -1;
        mapExtent = 0;
        windowHighWater = 0;
        FarTerrainViewArea.reset();
        FarTerrainNeighborView.getInstance().onLevelGone();
        clearPrefetch();
        FarTerrainEnclosure.reset();
        FarTerrainAutoDistance.reset();
        // A warp is exactly when the garden underneath can become somebody else's.
        FarTerrainOwnership.reset();
        earlyBuffer.clear();
        queueCenterX = Integer.MIN_VALUE;
        radiusLevel = null;
        appliedRadius = -1;
        decodeFailures = 0;
        servedCount = 0;
        cacheRejects = 0;
        FarTerrainStore old = active;
        active = null;
        if (old != null) {
            submitIo(old::save);
            // An ephemeral map is dropped here rather than parked - one server hop is its lifetime.
            parked = old.ephemeral() ? null : old;
            // The map's file was just rewritten with this stay's captures, so the neighbour view
            // must drop its read-only copy and re-read - a cache entry from before the visit can
            // even be an empty read of a file that did not exist yet.
            FarTerrainNeighborView.getInstance().refreshStore(old.map());
        }
    }

    // ------------------------------------------------------------------ settings hooks

    /** The settings page's status line. */
    public String statusLine() {
        FarTerrainStore store = active;
        String where = islandName == null ? "" : " (" + islandName + ")";
        if (store == null) {
            return mapName != null ? mapName + where + ": loading..." : "No supported island active";
        }
        String line = store.map() + where + ": " + store.size() + " remembered ("
                + (store.heapBytes() / (1024 * 1024)) + " MB), " + servedCount + " served";
        if (store.ephemeral()) {
            line += " §8(session only)";
        }
        Integer clamp = drawChunks();
        if (clamp != null) {
            String why = FarTerrainEnclosure.engaged()
                    ? (FarTerrainAutoDistance.engaged() ? "enclosed + fps" : "enclosed") : "fps";
            line += " §b(" + why + " - drawing " + clamp + " ch, all still loaded)";
        }
        if (cacheRejects > 0) {
            line += " §c(" + cacheRejects + " refused - see log)";
        }
        return line;
    }

    /**
     * The settings page's delete button: wipes the current map's file and memory. On the shared map
     * that is every island on it at once - they are one file because they are one coordinate space.
     */
    public void deleteCurrentMap() {
        FarTerrainStore store = active;
        if (store == null) {
            return;
        }
        serveOrder = null;
        serveIndex = 0;
        submitIo(store::deleteFile);
    }

    /**
     * Wipes every map's remembered terrain and every island-claim record - the clean slate before
     * re-capturing the world from scratch.
     *
     * <p>Deleting the files is not enough on its own: the active store, the neighbour view's
     * loaded stores and the claim record all hold their map in memory and would write it straight
     * back on the next save tick. So the memory is cleared first, on this thread, and only then
     * are the files removed on the worker.
     *
     * <p>It exists because the alternative - visiting each map in turn to press the single-map
     * delete - cannot reach a map whose island you are not standing on, and a half-deleted set is
     * exactly the state that makes remembered terrain untrustworthy.
     */
    public void deleteAllMaps() {
        Minecraft minecraft = Minecraft.getInstance();
        FarTerrainNeighborView.getInstance().forgetStores(minecraft.level);
        serveOrder = null;
        serveIndex = 0;
        clearPrefetch();
        earlyBuffer.clear();
        FarTerrainStore store = active;
        FarTerrainStore parkedStore = parked;
        FarTerrainClaims record = claims;
        submitIo(() -> {
            if (store != null) {
                store.deleteFile();
            }
            if (parkedStore != null && parkedStore != store) {
                parkedStore.deleteFile();
            }
            if (record != null) {
                record.deleteFile();
            }
            deleteRenderFiles();
        });
    }

    /**
     * Removes every terrain and claim file in the render folder. Restricted to the two suffixes
     * this module writes - a blanket directory wipe would be a promise about other people's files
     * that this method is in no position to make.
     */
    private static void deleteRenderFiles() {
        java.nio.file.Path dir = SBSFiles.root().resolve("render");
        int deleted = 0;
        try (var listing = java.nio.file.Files.list(dir)) {
            for (java.nio.file.Path path : listing.toList()) {
                String name = path.getFileName().toString();
                if (!name.endsWith(".sbsr") && !name.endsWith("_islands.json")) {
                    continue;
                }
                try {
                    if (java.nio.file.Files.deleteIfExists(path)) {
                        deleted++;
                    }
                } catch (java.io.IOException e) {
                    sbs.modid.SkyblockSimplifiedSBS.LOGGER.warn(
                            "[SBS][Terrain] could not delete {} ({})", name, e.toString());
                }
            }
        } catch (java.io.IOException e) {
            sbs.modid.SkyblockSimplifiedSBS.LOGGER.warn(
                    "[SBS][Terrain] could not list the render folder ({})", e.toString());
            return;
        }
        sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Terrain] deleted ALL remembered terrain - {} file(s) removed from {}",
                deleted, dir);
    }
}
