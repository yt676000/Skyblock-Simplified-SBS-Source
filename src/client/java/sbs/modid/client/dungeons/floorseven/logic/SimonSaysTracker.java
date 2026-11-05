/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.floorseven.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.sound.SoundListeners;
import sbs.modid.client.dungeons.events.DungeonEvents;
import sbs.modid.client.dungeons.run.logic.DungeonStateManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the phase-3 "Simon Says" device: the wall of buttons whose lights flash a sequence you then
 * repeat. Records <b>the order the lights come on in</b>, which is the one thing about that device
 * you cannot get back once it has finished flashing.
 *
 * <p><b>Nothing about the device's shape is assumed.</b> No coordinates, no grid, no wall detection:
 * the tracker looks for buttons near the player and asks, per button, whether the block it is stuck
 * to is giving off light. That is the entire model.
 *
 * <p><b>What it does not do.</b> It does not track which buttons you have already pressed: a button's
 * powered state lasts a moment and says nothing about whether the device accepted the press. So the
 * whole numbered sequence stays on screen ({@link #next()} is always 0). Instead every press near the
 * device opens a short probe window ({@link SimonSaysProbe}) and the reactions inside it - light
 * changes and sounds - are logged once each as {@code [SBS][SimonSays] probe ...}, so the accept
 * signal can be found in an instance log and "next" advanced on it later. Nothing is ever pressed
 * for you.
 *
 * <p>A sequence that replays from the start each round folds into the same list (see
 * {@link SimonSaysSequence}). It is cleared when a device completes ({@link #onDeviceCompleted}) or
 * after {@link SimonSaysSequence#IDLE_RESET_MS} of nothing new.
 */
public final class SimonSaysTracker {

    private static final SimonSaysTracker INSTANCE = new SimonSaysTracker();

    /** One recorded step: the button and the direction its pressable face points. */
    public record Step(BlockPos pos, Direction normal) {
    }

    /** How far around the player buttons are looked for. The device is read from arm's length. */
    private static final int RADIUS_XZ = 8;
    private static final int RADIUS_Y = 4;

    /** Four scans a second: the flashes last far longer than that, and a scan is a small cube. */
    private static final long SCAN_INTERVAL_MS = 250L;

    /** Light level at which a backing block counts as "on". Sea lanterns and glowstone are 15. */
    private static final int LIT_LEVEL = 10;

    /** A sound this close to the last pressed button counts as the device's reaction. */
    private static final double PROBE_SOUND_RADIUS_SQ = 8.0 * 8.0;

    private final SimonSaysSequence sequence = new SimonSaysSequence();
    private final SimonSaysProbe probe = new SimonSaysProbe();
    private final Map<Long, Direction> normals = new HashMap<>();
    private final SoundListeners.Listener soundListener = this::onSound;

    /** Immutable, replaced only when the order grows - the renderer reads it without copying. */
    private volatile List<Step> steps = List.of();
    /** Per step, whether the eye can see the button face. Updated each scan, read by the renderer. */
    private volatile boolean[] visible = new boolean[0];

    private Set<Long> poweredBefore = Set.of();
    private Set<Long> litBefore = Set.of();
    private BlockPos lastPress;
    private long lastScanMs;
    private boolean logged;
    private boolean subscribed;

    private SimonSaysTracker() {
    }

    public static SimonSaysTracker getInstance() {
        return INSTANCE;
    }

    /** The remembered sequence, first light first. Empty when there is nothing to show. */
    public List<Step> steps() {
        return steps;
    }

    /** Whether step {@code i}'s face is in line of sight; false for an index out of range. */
    public boolean visible(int i) {
        boolean[] v = visible;
        return i >= 0 && i < v.length && v[i];
    }

    /**
     * The 0-based step to press now. Always 0: no reliable accept signal is known, so a press never
     * advances it. When the probe log shows one, this is the one place that changes.
     */
    public int next() {
        return 0;
    }

    /** Whether the device solver should be reading and drawing right now. */
    public boolean enabled() {
        DungeonStateManager state = DungeonStateManager.getInstance();
        return ConfigManager.getInstance().get().dungeons.deviceSolver
                && state.inDungeon() && state.floorNumber() == 7
                && state.phase() == DungeonEvents.Phase.BOSS
                && FloorSevenPhaseTimer.getInstance().current() == FloorSevenPhaseTimer.Phase.GOLDOR;
    }

    /** Called every client tick; throttles itself and does nothing outside the Goldor phase. */
    public void onClientTick() {
        boolean on = enabled();
        subscribe(on);
        if (!on) {
            reset();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastScanMs < SCAN_INTERVAL_MS) {
            return;
        }
        lastScanMs = now;
        scan(now);
    }

    /**
     * A device was completed (TerminalTracker's "completed a device!" line). The lights for this
     * device are no longer needed, and the next section's device must not inherit them.
     */
    public void onDeviceCompleted(String player) {
        if (sequence.size() > 0) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][SimonSays] cleared: device completed by {} ({} steps)",
                    player, sequence.size());
        }
        reset();
    }

    private void subscribe(boolean want) {
        if (want == subscribed) {
            return;
        }
        if (want) {
            SoundListeners.add(soundListener);
        } else {
            SoundListeners.remove(soundListener);
        }
        subscribed = want;
    }

    private void scan(long now) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        Player player = minecraft.player;
        if (level == null || player == null) {
            return;
        }
        BlockPos centre = player.blockPosition();
        Set<Long> lit = new HashSet<>();
        Set<Long> powered = new HashSet<>();
        List<BlockPos> buttons = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(
                centre.offset(-RADIUS_XZ, -RADIUS_Y, -RADIUS_XZ),
                centre.offset(RADIUS_XZ, RADIUS_Y, RADIUS_XZ))) {
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof ButtonBlock)) {
                continue;
            }
            BlockPos button = pos.immutable();
            long key = button.asLong();
            buttons.add(button);
            if (backingLit(level, button)) {
                lit.add(key);
                normals.computeIfAbsent(key, k -> normalOf(state));
            }
            if (state.getValue(ButtonBlock.POWERED)) {
                powered.add(key);
            }
        }
        if (buttons.isEmpty()) {
            return; // nowhere near the device
        }
        probePresses(powered, now);
        probeLights(lit, now);

        if (sequence.observe(lit, now)) {
            publish();
        }
        if (!lit.isEmpty() && !logged) {
            logged = true;
            log(buttons, lit);
        }
        if (sequence.idle(now)) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][SimonSays] cleared: idle for {}s",
                    SimonSaysSequence.IDLE_RESET_MS / 1000);
            reset();
            return;
        }
        updateVisibility(level, player);
    }

    /** A button that just went powered: open a probe window and log the press once. */
    private void probePresses(Set<Long> powered, long now) {
        for (Long key : powered) {
            if (poweredBefore.contains(key)) {
                continue;
            }
            probe.onPress(now);
            lastPress = BlockPos.of(key);
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][SimonSays] probe press {},{},{} step={} of {}",
                    lastPress.getX(), lastPress.getY(), lastPress.getZ(),
                    sequence.indexOf(key) + 1, sequence.size());
        }
        poweredBefore = powered;
    }

    /** Lights that changed inside a press window: one line per window per changed light state. */
    private void probeLights(Set<Long> lit, long now) {
        if (probe.inWindow(now)) {
            for (Long key : lit) {
                if (!litBefore.contains(key)) {
                    logLight(key, "on", now);
                }
            }
            for (Long key : litBefore) {
                if (!lit.contains(key)) {
                    logLight(key, "off", now);
                }
            }
        }
        litBefore = lit;
    }

    private void logLight(long key, String change, long now) {
        if (probe.firstInWindow("light:" + key + ":" + change, now)) {
            BlockPos pos = BlockPos.of(key);
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][SimonSays] probe light {} {},{},{} +{}ms",
                    change, pos.getX(), pos.getY(), pos.getZ(), probe.sincePress(now));
        }
    }

    /** Client thread, once per sound while subscribed. Record-and-return only. */
    private void onSound(SoundInstance instance) {
        BlockPos press = lastPress;
        if (press == null || instance.getIdentifier() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!probe.inWindow(now)) {
            return;
        }
        double dx = instance.getX() - (press.getX() + 0.5);
        double dy = instance.getY() - (press.getY() + 0.5);
        double dz = instance.getZ() - (press.getZ() + 0.5);
        if (dx * dx + dy * dy + dz * dz > PROBE_SOUND_RADIUS_SQ) {
            return;
        }
        String id = instance.getIdentifier().toString();
        if (probe.firstInWindow("sound:" + id, now)) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][SimonSays] probe sound {} pitch={} +{}ms",
                    id, instance.getPitch(), probe.sincePress(now));
        }
    }

    /** Rebuild the immutable step list. Only on growth, never per frame. */
    private void publish() {
        List<Step> built = new ArrayList<>(sequence.size());
        for (int i = 0; i < sequence.size(); i++) {
            long key = sequence.get(i);
            built.add(new Step(BlockPos.of(key), normals.getOrDefault(key, Direction.UP)));
        }
        steps = List.copyOf(built);
    }

    /** Depth test for the HUD-layer marks: a step is drawn only when its face is in line of sight. */
    private void updateVisibility(ClientLevel level, Player player) {
        List<Step> current = steps;
        boolean[] v = visible.length == current.size() ? visible : new boolean[current.size()];
        Vec3 eye = player.getEyePosition();
        for (int i = 0; i < current.size(); i++) {
            Step step = current.get(i);
            BlockPos pos = step.pos();
            Vec3 target = new Vec3(pos.getX() + 0.5 + step.normal().getStepX() * 0.3,
                    pos.getY() + 0.5 + step.normal().getStepY() * 0.3,
                    pos.getZ() + 0.5 + step.normal().getStepZ() * 0.3);
            BlockHitResult hit = level.clip(new ClipContext(
                    eye, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            v[i] = hit.getType() != HitResult.Type.BLOCK || hit.getBlockPos().equals(pos);
        }
        visible = v;
    }

    /** The direction the button's pressable face points: up for a floor button, down for a ceiling one. */
    private static Direction normalOf(BlockState state) {
        AttachFace face = state.getValue(ButtonBlock.FACE);
        if (face == AttachFace.FLOOR) {
            return Direction.UP;
        }
        if (face == AttachFace.CEILING) {
            return Direction.DOWN;
        }
        return state.getValue(ButtonBlock.FACING);
    }

    /**
     * Whether the block this button is stuck to is giving off light. All six neighbours are asked
     * rather than trusting the attachment property.
     */
    private static boolean backingLit(ClientLevel level, BlockPos button) {
        for (Direction direction : Direction.values()) {
            if (level.getBlockState(button.relative(direction)).getLightEmission() >= LIT_LEVEL) {
                return true;
            }
        }
        return false;
    }

    /** One-shot dump of the device as it was first seen lit, for checking the reading after a run. */
    private static void log(List<BlockPos> buttons, Set<Long> lit) {
        StringBuilder text = new StringBuilder();
        for (BlockPos button : buttons) {
            text.append(button.getX()).append(',').append(button.getY()).append(',').append(button.getZ())
                    .append(lit.contains(button.asLong()) ? "=LIT" : "=off").append(' ');
        }
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][SimonSays] {} buttons, {} lit | {}",
                buttons.size(), lit.size(), text.toString().trim());
    }

    private void reset() {
        sequence.clear();
        probe.reset();
        normals.clear();
        if (!steps.isEmpty()) {
            steps = List.of();
            visible = new boolean[0];
        }
        poweredBefore = Set.of();
        litBefore = Set.of();
        lastPress = null;
        logged = false;
    }
}
