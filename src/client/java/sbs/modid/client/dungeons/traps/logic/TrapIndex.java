/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.traps.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.dungeons.run.logic.DungeonStateManager;
import sbs.modid.client.dungeons.traps.model.Trap;
import sbs.modid.client.dungeons.traps.model.TripwireRun;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * The dungeon's tripwire and dispenser blocks, indexed once and then kept current by events.
 *
 * <p><b>Why there is no recurring scan.</b> A timer that re-sweeps the world is the kind of cost
 * nobody notices until it turns up months later as an unexplained frame-rate complaint, and it is
 * unnecessary: the client is already told about every change. A chunk arriving is indexed then; a
 * chunk leaving drops its entries; and {@code BlockUpdateMixin} sees every server-driven block change
 * with the old state still readable, which is precisely "a dispenser just appeared" and "that wire is
 * gone" as facts rather than as the result of looking again. After the initial fill this index costs
 * nothing per tick until something actually changes.
 *
 * <p><b>The initial fill is paid for by the palette, not by the blocks.</b> A 16x16x16 section whose
 * palette contains no tripwire and no dispenser cannot contain one, and
 * {@code PalettedContainerRO#maybeHas} answers that without touching a single position - so a chunk
 * costs a handful of predicate calls and only the rare section that really holds a trap is swept.
 * What is left is spread over several ticks against a budget, the same arrangement
 * {@code PathfinderTask} uses and for the same reason: block reads stay on the thread that owns the
 * chunks, and a big room costs a few ticks instead of one long stall.
 *
 * <p><b>Gated hard.</b> Nothing is indexed, and no tick does any work, unless a dungeon run is
 * actually in progress - which is {@link DungeonStateManager}, the signal that tells a run apart from
 * standing in the entrance. Leaving drops the whole index: a room's traps belong to the run they were
 * seen in, and remembering them into the next one would draw boxes over an empty corridor.
 */
public final class TrapIndex {

    private static final TrapIndex INSTANCE = new TrapIndex();

    /**
     * Work allowed per client tick. A palette check costs one, sweeping a section that passed it
     * costs {@link #SWEEP_COST} - so a tick does either a lot of cheap rejections or about one real
     * sweep, and never both to excess.
     */
    private static final int BUDGET_PER_TICK = 96;

    /** What one full 16x16x16 sweep is charged against {@link #BUDGET_PER_TICK}. */
    private static final int SWEEP_COST = 48;

    /** Blocks in a section, one axis. */
    private static final int SECTION = 16;

    /** Whether a palette holds anything worth sweeping for. The only per-section test. */
    private static final Predicate<BlockState> WANTED = state -> {
        var block = state.getBlock();
        return block == Blocks.TRIPWIRE || block == Blocks.TRIPWIRE_HOOK || block == Blocks.DISPENSER;
    };

    /** Packed block position -> trap. Written from the tick and the block-update hook, read on frames. */
    private final Map<Long, Trap> traps = new ConcurrentHashMap<>();

    /** Chunks already walked, so a chunk is never indexed twice while the run lasts. */
    private final Set<Long> indexed = new HashSet<>();

    /** Chunks waiting to be walked, drained against the budget. */
    private final Deque<Long> pending = new ArrayDeque<>();

    /** Cached wire grouping; rebuilt only when the wire set actually changed. */
    private volatile List<TripwireRun> runs = List.of();
    private volatile boolean runsDirty;

    private boolean active;
    private long lastLogAt;

    private TrapIndex() {
    }

    public static TrapIndex getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.TrapHighlightSettings cfg() {
        return ConfigManager.getInstance().get().trapHighlight;
    }

    /** Whether the feature may do anything at all right now. */
    public static boolean armed() {
        SBSConfig.TrapHighlightSettings settings = cfg();
        return settings.enabled && (settings.tripwires || settings.dispensers)
                && DungeonStateManager.getInstance().inDungeon();
    }

    // ------------------------------------------------------------------ read side

    /** Every indexed dispenser. Empty outside a run. */
    public List<Trap> dispensers() {
        List<Trap> out = new ArrayList<>();
        for (Trap trap : traps.values()) {
            if (trap.kind() == Trap.Kind.DISPENSER) {
                out.add(trap);
            }
        }
        return out;
    }

    /** The wire, grouped into runs. Regrouped lazily, so a frame never pays for it twice. */
    public List<TripwireRun> tripwireRuns() {
        if (runsDirty) {
            runsDirty = false;
            runs = groupWire();
        }
        return runs;
    }

    /** How many blocks are indexed - for the settings status line and the diagnostic. */
    public int size() {
        return traps.size();
    }

    // ------------------------------------------------------------------ tick

    public void tick(Minecraft minecraft) {
        if (minecraft == null || !armed()) {
            if (active) {
                clear();
            }
            return;
        }
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null) {
            return;
        }
        if (!active) {
            // First tick of a run: everything already streamed in has to be walked once, because no
            // load event will ever fire for it. From here on the events carry it.
            active = true;
            seedLoadedChunks(minecraft, level, player.blockPosition());
        }
        drain(level);
        diagnostic();
    }

    /**
     * Queues every chunk currently loaded around the player.
     *
     * <p>Asking the chunk source for its loaded set is not something the client exposes, so the grid
     * is walked and {@link ClientLevel#hasChunk} is the test. It runs once per run rather than on a
     * timer - a chunk that streams in later announces itself through {@link #onChunkLoaded}.
     */
    private void seedLoadedChunks(Minecraft minecraft, ClientLevel level, BlockPos around) {
        int radius = Math.max(2, minecraft.options.getEffectiveRenderDistance());
        int centreX = around.getX() >> 4;
        int centreZ = around.getZ() >> 4;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = centreX + dx;
                int z = centreZ + dz;
                if (level.hasChunk(x, z)) {
                    enqueue(ChunkPos.pack(x, z));
                }
            }
        }
    }

    /** Walks queued chunks until the tick's budget is spent. */
    private void drain(ClientLevel level) {
        int budget = BUDGET_PER_TICK;
        while (budget > 0 && !pending.isEmpty()) {
            long key = pending.peek();
            int x = ChunkPos.getX(key);
            int z = ChunkPos.getZ(key);
            if (!level.hasChunk(x, z)) {
                pending.poll();   // unloaded again before we got to it
                indexed.remove(key);
                continue;
            }
            budget -= indexChunk(level, level.getChunk(x, z));
            pending.poll();
        }
    }

    /**
     * Indexes one chunk, returning what it cost. Air-only sections and sections whose palette cannot
     * hold a trap are rejected outright - which is nearly all of them, and is what makes the fill
     * affordable.
     */
    private int indexChunk(ClientLevel level, LevelChunk chunk) {
        int spent = 0;
        LevelChunkSection[] sections = chunk.getSections();
        int minSectionY = level.getMinSectionY();
        for (int index = 0; index < sections.length; index++) {
            LevelChunkSection section = sections[index];
            spent++;
            if (section == null || section.hasOnlyAir() || !section.getStates().maybeHas(WANTED)) {
                continue;
            }
            spent += SWEEP_COST;
            sweepSection(section, chunk.getPos(), (minSectionY + index) << 4);
        }
        return spent;
    }

    /** The one place a block position is actually read - only for a section the palette vouched for. */
    private void sweepSection(LevelChunkSection section, ChunkPos chunkPos, int baseY) {
        int baseX = chunkPos.getMinBlockX();
        int baseZ = chunkPos.getMinBlockZ();
        for (int y = 0; y < SECTION; y++) {
            for (int x = 0; x < SECTION; x++) {
                for (int z = 0; z < SECTION; z++) {
                    BlockState state = section.getBlockState(x, y, z);
                    if (WANTED.test(state)) {
                        record(new BlockPos(baseX + x, baseY + y, baseZ + z), state);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ maintenance

    /** A chunk arrived: queue it. Costs nothing until the tick gets round to it. */
    public void onChunkLoaded(ChunkPos pos) {
        if (active && pos != null) {
            enqueue(pos.pack());
        }
    }

    /**
     * A chunk left: drop everything in it.
     *
     * <p>Not remembered, deliberately. A trap in an unloaded chunk cannot be verified, and a box
     * drawn from memory over a room the client can no longer see is exactly the kind of confident
     * wrong answer this codebase refuses elsewhere.
     */
    public void onChunkUnloaded(LevelChunk chunk) {
        if (!active || chunk == null) {
            return;
        }
        long key = chunk.getPos().pack();
        indexed.remove(key);
        pending.remove(key);
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        boolean removed = traps.keySet().removeIf(packed -> {
            BlockPos pos = BlockPos.of(packed);
            return pos.getX() >= minX && pos.getX() < minX + SECTION
                    && pos.getZ() >= minZ && pos.getZ() < minZ + SECTION;
        });
        if (removed) {
            runsDirty = true;
        }
    }

    /**
     * A block the server changed. The old state is still in the level here, so this is both halves of
     * the transition: a trap that stopped being one is removed, a new one is added, and a wire that
     * merely changed its powered flag updates in place.
     */
    public void onBlockChanged(BlockPos pos, BlockState before, BlockState after) {
        if (!active || pos == null || after == null) {
            return;
        }
        boolean wasTrap = before != null && WANTED.test(before);
        boolean isTrap = WANTED.test(after);
        if (!wasTrap && !isTrap) {
            return;
        }
        if (isTrap) {
            record(pos, after);
        } else {
            Trap gone = traps.remove(pos.asLong());
            if (gone != null && gone.isWire()) {
                runsDirty = true;
            }
        }
    }

    /** Adds or replaces one trap. Wire changes mark the grouping stale; dispensers never do. */
    private void record(BlockPos pos, BlockState state) {
        Trap trap = read(pos.immutable(), state);
        if (trap == null) {
            return;
        }
        Trap previous = traps.put(pos.asLong(), trap);
        if (trap.isWire() && (previous == null || !previous.isWire())) {
            runsDirty = true;
        }
    }

    /** One block state turned into a {@link Trap}, with facing and trigger state read off it. */
    private static Trap read(BlockPos pos, BlockState state) {
        var block = state.getBlock();
        if (block == Blocks.DISPENSER) {
            Direction facing = state.hasProperty(DispenserBlock.FACING)
                    ? state.getValue(DispenserBlock.FACING) : null;
            boolean triggered = state.hasProperty(DispenserBlock.TRIGGERED)
                    && state.getValue(DispenserBlock.TRIGGERED);
            return new Trap(Trap.Kind.DISPENSER, pos, facing, triggered);
        }
        if (block == Blocks.TRIPWIRE) {
            boolean triggered = (state.hasProperty(TripWireBlock.POWERED)
                    && state.getValue(TripWireBlock.POWERED))
                    || (state.hasProperty(TripWireBlock.DISARMED)
                    && state.getValue(TripWireBlock.DISARMED));
            return new Trap(Trap.Kind.TRIPWIRE, pos, null, triggered);
        }
        if (block == Blocks.TRIPWIRE_HOOK) {
            boolean triggered = state.hasProperty(TripWireHookBlock.POWERED)
                    && state.getValue(TripWireHookBlock.POWERED);
            return new Trap(Trap.Kind.HOOK, pos, null, triggered);
        }
        return null;
    }

    private void enqueue(long key) {
        if (indexed.add(key)) {
            pending.add(key);
        }
    }

    /** Run over, island changed, or the feature switched off: none of it applies any more. */
    public void clear() {
        active = false;
        traps.clear();
        indexed.clear();
        pending.clear();
        runs = List.of();
        runsDirty = false;
    }

    // ------------------------------------------------------------------ grouping

    /**
     * Contiguous wire and its hooks, grouped into runs by flood fill over the six neighbours.
     *
     * <p>Hooks are pulled in as part of the run rather than kept separate: they are the ends the wire
     * is strung between, and a run drawn to the last wire block stops one short of its anchor, which
     * reads as a gap wide enough to walk through.
     */
    private List<TripwireRun> groupWire() {
        Set<Long> wire = new HashSet<>();
        for (Map.Entry<Long, Trap> entry : traps.entrySet()) {
            if (entry.getValue().isWire()) {
                wire.add(entry.getKey());
            }
        }
        return group(wire);
    }

    /**
     * The grouping itself, over packed positions and nothing else.
     *
     * <p>Separated from the index so it can be exercised on a bench: whether two wires a block apart
     * are one line or two is the kind of rule that is easy to get subtly wrong and impossible to
     * check by looking at a corridor. {@code TrapGroupingTest} is what holds it.
     */
    static List<TripwireRun> group(Set<Long> wire) {
        Set<Long> remaining = new HashSet<>(wire);
        List<TripwireRun> found = new ArrayList<>();
        Deque<Long> frontier = new ArrayDeque<>();
        while (!remaining.isEmpty()) {
            long seed = remaining.iterator().next();
            remaining.remove(seed);
            frontier.add(seed);
            BlockPos first = BlockPos.of(seed);
            int minX = first.getX();
            int minY = first.getY();
            int minZ = first.getZ();
            int maxX = minX;
            int maxY = minY;
            int maxZ = minZ;
            int count = 0;
            while (!frontier.isEmpty()) {
                BlockPos pos = BlockPos.of(frontier.poll());
                count++;
                minX = Math.min(minX, pos.getX());
                minY = Math.min(minY, pos.getY());
                minZ = Math.min(minZ, pos.getZ());
                maxX = Math.max(maxX, pos.getX());
                maxY = Math.max(maxY, pos.getY());
                maxZ = Math.max(maxZ, pos.getZ());
                for (Direction direction : Direction.values()) {
                    long neighbour = pos.relative(direction).asLong();
                    if (remaining.remove(neighbour)) {
                        frontier.add(neighbour);
                    }
                }
            }
            found.add(new TripwireRun(new BlockPos(minX, minY, minZ),
                    new BlockPos(maxX, maxY, maxZ), count));
        }
        return List.copyOf(found);
    }

    // ------------------------------------------------------------------ diagnostics

    /**
     * A throttled line while a fill is still running or the index has content.
     *
     * <p>It carries the triggered counts on purpose: whether a sprung trap stays dangerous in the
     * Catacombs is the one open question this feature has, and this is what lets it be settled from a
     * real run rather than guessed at.
     */
    private void diagnostic() {
        long now = System.currentTimeMillis();
        if (now - lastLogAt < 15_000L || traps.isEmpty()) {
            return;
        }
        lastLogAt = now;
        int wire = 0;
        int hooks = 0;
        int dispensers = 0;
        int triggered = 0;
        for (Trap trap : traps.values()) {
            switch (trap.kind()) {
                case TRIPWIRE -> wire++;
                case HOOK -> hooks++;
                case DISPENSER -> dispensers++;
            }
            if (trap.triggered()) {
                triggered++;
            }
        }
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Traps] wire={} hooks={} dispensers={} triggered={} runs={} pendingChunks={}",
                wire, hooks, dispensers, triggered, tripwireRuns().size(), pending.size());
    }
}
