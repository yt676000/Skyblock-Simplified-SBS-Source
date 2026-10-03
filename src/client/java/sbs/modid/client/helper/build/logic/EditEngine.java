/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.build.logic.BlockStates;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.helper.build.model.EditHistory;
import sbs.modid.client.helper.build.model.EditPlan;
import sbs.modid.client.helper.build.model.EditRecord;
import sbs.modid.client.helper.build.model.EditShapes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Applies edits to a singleplayer world, a slice per game tick, on the integrated server's thread.
 *
 * <p><b>The world is only ever touched from {@code server.execute}</b> - never from the render thread
 * - and each slice re-checks that the integrated server it was queued for is still the one running
 * before it reads or sets a single block. There is no path from here to a remote server: without an
 * integrated server nothing is queued at all ({@link BuildGate}).
 *
 * <p>Pacing: the client tick hands the server one slice of {@code blocksPerTick} blocks and waits for
 * it to come back before handing over the next, so a million-block edit spreads over a few seconds
 * with a progress bar instead of freezing the game. Blocks are set the way the game's own fill command
 * sets them - clients updated, every side effect skipped - so nothing drops, no neighbour reacts and a
 * replaced chest's contents are not spilled (they are kept in the undo record instead).
 *
 * <p>Every applied edit becomes a {@link Timeline} entry holding each position's state before and
 * after, so it can be undone, redone, or jumped over from the timeline screen. A cancelled edit keeps
 * what it already placed and records exactly that part, named as stopped.
 */
public final class EditEngine {

    /** Clients updated, all side effects skipped - the flags vanilla's strict fill uses. */
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS;

    /** Largest single edit, in positions. */
    public static final int MAX_EDIT_BLOCKS = 4_000_000;

    /** One run of positions and the states they get. */
    private static final class Segment {
        final long[] positions;
        final char[] targets;
        final BlockState[] states;
        final Map<Integer, String> entities;

        Segment(long[] positions, char[] targets, List<String> palette, Map<Integer, String> entities) {
            this.positions = positions;
            this.targets = targets;
            this.states = BlockStates.resolve(palette);
            this.entities = entities;
        }
    }

    /** A queued edit or replay. Mutated on the server thread only while {@link #inFlight}. */
    private static final class Job {
        final String name;
        final boolean record;
        final List<Segment> segments;
        final int total;
        final ResourceKey<Level> dimension;
        final IntegratedServer server;
        final long startedAt = System.currentTimeMillis();
        final Runnable onFinish;
        final EditPlan plan;

        // Progress: which segment, which position in it, and how many positions are done overall.
        int segment;
        int index;
        int done;
        volatile boolean inFlight;
        volatile boolean cancelRequested;
        volatile String failure;

        // The undo record being built (edits only).
        char[] before;
        List<String> recordPalette;
        Map<String, Integer> recordIndex;
        Map<BlockState, Integer> stateIndex;
        Map<Integer, String> beforeEntities;
        int changed;

        Job(String name, boolean record, List<Segment> segments, ResourceKey<Level> dimension,
            IntegratedServer server, Runnable onFinish, EditPlan plan) {
            this.name = name;
            this.record = record;
            this.segments = segments;
            int sum = 0;
            for (Segment s : segments) {
                sum += s.positions.length;
            }
            this.total = sum;
            this.dimension = dimension;
            this.server = server;
            this.onFinish = onFinish;
            this.plan = plan;
            if (record) {
                before = new char[sum];
                recordPalette = new ArrayList<>(plan.palette());
                recordIndex = new HashMap<>();
                for (int i = 0; i < recordPalette.size(); i++) {
                    recordIndex.put(recordPalette.get(i), i);
                }
                stateIndex = new IdentityHashMap<>();
                beforeEntities = new HashMap<>();
            }
        }
    }

    private static volatile Job current;

    static {
        BuildSession.onLeave(() -> current = null);
    }

    private EditEngine() {
    }

    public static boolean busy() {
        return current != null;
    }

    /** 0-1 progress of the running job, or -1 when idle. */
    public static float progress() {
        Job job = current;
        return job == null || job.total == 0 ? -1f : (float) job.done / job.total;
    }

    /** "set stone  •  12,000 / 40,000", or {@code null} when idle. */
    public static String label() {
        Job job = current;
        if (job == null) {
            return null;
        }
        return job.name + "  •  " + String.format(Locale.ROOT, "%,d / %,d", job.done, job.total)
                + (job.record ? "  •  //cancel stops it" : "");
    }

    /**
     * Queues {@code plan} for the open singleplayer world. Refuses (with a chat line) off
     * singleplayer, while another edit runs, or past {@link #MAX_EDIT_BLOCKS}.
     */
    public static boolean submit(EditPlan plan) {
        if (!ready(plan.size())) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Segment segment = new Segment(plan.positionsCopy(), plan.targetsCopy(), plan.palette(), plan.blockEntities());
        current = new Job(plan.name(), true, List.of(segment), minecraft.level.dimension(),
                BuildGate.server(), null, plan);
        return true;
    }

    /**
     * Replays timeline steps (undo, redo or a jump), then moves the timeline's cursor to
     * {@code targetCursor}. A replay cannot be cancelled halfway: stopping it would leave the world
     * between two timeline entries, matching neither.
     */
    public static boolean replay(List<EditHistory.Step<EditRecord>> steps, int targetCursor, String name) {
        if (steps.isEmpty()) {
            return false;
        }
        long total = 0;
        for (EditHistory.Step<EditRecord> step : steps) {
            total += step.entry().record().positions().length;
        }
        if (!ready(total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total, false)) {
            return false;
        }
        List<Segment> segments = new ArrayList<>();
        for (EditHistory.Step<EditRecord> step : steps) {
            EditRecord record = step.entry().record();
            segments.add(new Segment(record.positions(), step.backward() ? record.before() : record.after(),
                    record.palette(), step.backward() ? record.beforeEntities() : record.afterEntities()));
        }
        Minecraft minecraft = Minecraft.getInstance();
        current = new Job(name, false, segments, minecraft.level.dimension(), BuildGate.server(),
                () -> Timeline.history().moveCursor(targetCursor), null);
        return true;
    }

    private static boolean ready(int size) {
        return ready(size, true);
    }

    private static boolean ready(int size, boolean capped) {
        if (!BuildGate.singleplayer() || Minecraft.getInstance().level == null) {
            BuildChat.warn(sbs.modid.client.helper.build.command.BuildCommand.SINGLEPLAYER_ONLY);
            return false;
        }
        if (current != null) {
            BuildChat.warn("Another edit is still running - wait for it, or //cancel it");
            return false;
        }
        if (capped && size > MAX_EDIT_BLOCKS) {
            BuildChat.warn(String.format(Locale.ROOT, "That edit touches %,d blocks; the limit is %,d", size,
                    MAX_EDIT_BLOCKS));
            return false;
        }
        return true;
    }

    /** Asks the running edit to stop after its current slice. Replays refuse. */
    public static boolean cancel() {
        Job job = current;
        if (job == null) {
            return false;
        }
        if (!job.record) {
            BuildChat.warn("An undo or redo cannot be stopped halfway - it finishes in a moment");
            return true;
        }
        job.cancelRequested = true;
        return true;
    }

    /** Client tick: hand the server the next slice, or finish. */
    public static void tick() {
        Job job = current;
        if (job == null || job.inFlight) {
            return;
        }
        if (BuildGate.server() != job.server) {
            // The world closed under the edit; nothing more may be touched.
            current = null;
            return;
        }
        if (job.failure != null) {
            finish(job, "failed: " + job.failure);
            return;
        }
        if (job.cancelRequested) {
            finish(job, "stopped");
            return;
        }
        if (job.done >= job.total) {
            finish(job, null);
            return;
        }
        int slice = Math.max(1_000, Math.min(100_000, ConfigManager.getInstance().get().buildTools.blocksPerTick));
        job.inFlight = true;
        job.server.execute(() -> runSlice(job, slice));
    }

    /** Server thread. Sets up to {@code slice} blocks and hands control back. */
    private static void runSlice(Job job, int slice) {
        try {
            // Re-checked here, on the thread that touches the world: only the integrated server this
            // job was queued for, and only while it is still the one running.
            if (BuildGate.server() != job.server) {
                return;
            }
            ServerLevel level = job.server.getLevel(job.dimension);
            if (level == null) {
                job.failure = "that dimension is not loaded";
                return;
            }
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            int budget = slice;
            while (budget > 0 && job.segment < job.segments.size()) {
                Segment segment = job.segments.get(job.segment);
                if (job.index >= segment.positions.length) {
                    job.segment++;
                    job.index = 0;
                    continue;
                }
                int i = job.index;
                long packed = segment.positions[i];
                pos.set(EditShapes.unpackX(packed), EditShapes.unpackY(packed), EditShapes.unpackZ(packed));
                BlockState old = level.getBlockState(pos);
                BlockState target = segment.states[segment.targets[i]];
                if (job.record) {
                    recordBefore(job, level, pos, old);
                }
                if (old != target) {
                    level.setBlock(pos, target, FLAGS);
                    if (job.record) {
                        job.changed++;
                    }
                }
                String entity = segment.entities.get(i);
                if (entity != null) {
                    loadEntity(level, pos, target, entity);
                }
                job.index++;
                job.done++;
                budget--;
            }
        } catch (RuntimeException failed) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Build] Edit '{}' failed on the server thread", job.name, failed);
            job.failure = failed.getMessage() == null ? failed.getClass().getSimpleName() : failed.getMessage();
        } finally {
            job.inFlight = false;
        }
    }

    private static void recordBefore(Job job, ServerLevel level, BlockPos pos, BlockState old) {
        Integer index = job.stateIndex.get(old);
        if (index == null) {
            String serialized = BlockStates.serialize(old);
            index = job.recordIndex.get(serialized);
            if (index == null) {
                index = job.recordPalette.size();
                job.recordPalette.add(serialized);
                job.recordIndex.put(serialized, index);
            }
            job.stateIndex.put(old, index);
        }
        job.before[job.done] = (char) index.intValue();
        if (old.hasBlockEntity()) {
            BlockEntity entity = level.getBlockEntity(pos);
            if (entity != null) {
                try {
                    job.beforeEntities.put(job.done, entity.saveWithoutMetadata(level.registryAccess()).toString());
                } catch (RuntimeException unsaveable) {
                    SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Build] Could not save the block at {} for undo", pos);
                }
            }
        }
    }

    private static void loadEntity(ServerLevel level, BlockPos pos, BlockState state, String snbt) {
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity == null) {
            return;
        }
        try {
            CompoundTag tag = TagParser.parseCompoundFully(snbt);
            entity.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
            entity.setChanged();
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
        } catch (Exception unreadable) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Build] Could not restore the contents at {}: {}", pos,
                    unreadable.getMessage());
        }
    }

    /** Client thread: record the edit, report, clear. */
    private static void finish(Job job, String stoppedHow) {
        current = null;
        long millis = System.currentTimeMillis() - job.startedAt;
        if (job.record) {
            int done = job.done;
            long[] positions = java.util.Arrays.copyOf(job.plan.positionsCopy(), done);
            char[] after = java.util.Arrays.copyOf(job.plan.targetsCopy(), done);
            char[] before = java.util.Arrays.copyOf(job.before, done);
            Map<Integer, String> afterEntities = new HashMap<>();
            job.plan.blockEntities().forEach((index, snbt) -> {
                if (index < done) {
                    afterEntities.put(index, snbt);
                }
            });
            Map<Integer, String> beforeEntities = new HashMap<>();
            job.beforeEntities.forEach((index, snbt) -> {
                if (index < done) {
                    beforeEntities.put(index, snbt);
                }
            });
            if (done > 0) {
                String name = stoppedHow == null ? job.name
                        : job.name + " (" + stoppedHow + " at " + (done * 100L / Math.max(1, job.total)) + "%)";
                EditRecord record = new EditRecord(positions, before, after, List.copyOf(job.recordPalette),
                        beforeEntities, afterEntities, job.changed);
                Timeline.history().push(new EditHistory.Entry<>(name, System.currentTimeMillis(), job.changed, record));
            }
            String timing = String.format(Locale.ROOT, "%.1fs", millis / 1000.0);
            if (stoppedHow == null) {
                BuildChat.info(capitalise(job.name) + String.format(Locale.ROOT, "  •  %,d blocks changed  •  %s",
                        job.changed, timing));
                BuildChat.hint("//undo takes it back  •  //timeline shows every edit");
            } else {
                BuildChat.warn(capitalise(job.name) + " " + stoppedHow + String.format(Locale.ROOT,
                        " after %,d of %,d blocks - what was placed stays and can be undone", done, job.total));
            }
        } else {
            if (job.onFinish != null && stoppedHow == null) {
                job.onFinish.run();
            }
            BuildChat.info(capitalise(job.name) + (stoppedHow == null ? "" : " " + stoppedHow));
        }
    }

    private static String capitalise(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
