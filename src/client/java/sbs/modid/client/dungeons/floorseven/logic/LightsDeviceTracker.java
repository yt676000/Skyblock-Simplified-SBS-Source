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
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.state.BlockState;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.core.data.VersionedDataStore;
import sbs.modid.client.dungeons.events.DungeonEvents;
import sbs.modid.client.dungeons.floorseven.model.LightsCell;
import sbs.modid.client.dungeons.floorseven.model.LightsLayout;
import sbs.modid.client.dungeons.floorseven.model.LightsWiringData;
import sbs.modid.client.dungeons.run.logic.DungeonStateManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the phase-3 Lights device: a set of levers and redstone lamps, where - by our working model,
 * {@code ESTIMATED} and not yet seen in a capture - each lever toggles a fixed set of lamps and the
 * device completes once every lamp is lit.
 *
 * <p><b>Nothing about the build is assumed.</b> No coordinates, no grid size, no wiring. The tracker
 * finds levers and lamps near the player, freezes a box around them, and from then on watches that
 * box. The wiring is learned from what the server sends: each lever flip (ours or a teammate's - the
 * client cannot tell them apart and does not need to) and the lamps that changed with it, through
 * {@link LightsFlipAttributor}.
 *
 * <p><b>Probe output</b> ({@code [SBS][Lights]}): the layout once per device and again whenever it
 * changes, each lever and lamp relative to the layout's anchor with its state; then every attributed
 * flip as lever to lamps, every ambiguous window, and every lamp change no lever explains. That is
 * what the bundled wiring data is to be built from.
 *
 * <p><b>Solving.</b> Whenever the layout, a lamp or the wiring changes, {@link LightsDeviceState}
 * is re-evaluated from the board as it is - so a teammate's flip is accounted for the moment its
 * lamps change. The wiring is the bundled capture for this layout if there is one
 * ({@code f7_lights_wiring.json}, which ships empty), overridden per lever by what was watched.
 *
 * <p><b>Marks.</b> Which of the levers to flip can be seen from the camera is decided here, on the
 * scan cadence, with one outline ray per lever; the renderer draws only those, so a box never shows
 * through the wall it is behind.
 */
public final class LightsDeviceTracker {

    private static final String RESOURCE =
            "/assets/" + SkyblockSimplifiedSBS.MOD_ID + "/dungeons/f7_lights_wiring.json";

    /** Bundled-only: there is no backend route for this dataset yet. */
    private static final VersionedDataStore<LightsWiringData> STORE = new VersionedDataStore<>(
            "LightsWiring", RESOURCE, SBSFiles.root().resolve("data").resolve("f7_lights_wiring.json"),
            null, LightsWiringData.class, 1);

    private static final LightsDeviceTracker INSTANCE = new LightsDeviceTracker();

    /** Search cube around the player before a device is known. */
    private static final int RADIUS_XZ = 10;
    private static final int RADIUS_Y = 5;

    /** Margin around the known device that is rescanned, so a partly seen build grows to its edges. */
    private static final int MARGIN = 2;

    /** Forget the device once the player is this far (blocks, squared) from its box centre. */
    private static final double LEAVE_DISTANCE_SQ = 24.0 * 24.0;

    /** Scans every 5 ticks; block updates fill in between, the scan only repairs what was missed. */
    private static final int SCAN_INTERVAL_TICKS = 5;

    /** Two of each is the smallest thing that is a device and not a stray gate lever. */
    private static final int MIN_CELLS = 2;

    private boolean armed;
    private long tick;
    private long lastScanTick;

    /** The frozen search box, or {@code null} while no device is known. */
    private int[] box;
    private LightsLayout layout;
    private final Set<LightsCell> litLamps = new HashSet<>();
    private LightsLayout loggedLayout;

    private final LightsFlipAttributor attributor = new LightsFlipAttributor();

    /** Learned wiring, absolute lever to the absolute lamps it toggled when last seen alone. */
    private final Map<LightsCell, Set<LightsCell>> learned = new HashMap<>();

    /** Bumped whenever the layout, a lamp, or the wiring changes - the solver's cache key. */
    private int revision;

    private boolean storeLoaded;
    private int evaluatedRevision = -1;
    private LightsDeviceState state = LightsDeviceState.NONE;
    private LightsDeviceState.Kind loggedKind = LightsDeviceState.Kind.NONE;
    private int loggedFlips = -1;

    /** Per entry of {@code state.toFlip()}: whether the camera can see it. Rebuilt on the scan. */
    private boolean[] visible = new boolean[0];
    /** The device label, built when the state changes - never per frame. */
    private Component label = Component.empty();
    /** Where the label sits: above the centre of the layout. */
    private double labelX;
    private double labelY;
    private double labelZ;

    private LightsDeviceTracker() {
    }

    public static LightsDeviceTracker getInstance() {
        return INSTANCE;
    }

    /** Whether the feature should be reading the device right now. */
    public boolean enabled() {
        DungeonStateManager state = DungeonStateManager.getInstance();
        return ConfigManager.getInstance().get().dungeons.lightsDevice
                && state.inDungeon() && state.floorNumber() == 7
                && state.phase() == DungeonEvents.Phase.BOSS
                && FloorSevenPhaseTimer.getInstance().current() == FloorSevenPhaseTimer.Phase.GOLDOR;
    }

    /** One boolean read; the block-update hook returns on it outside the Goldor phase. */
    public boolean armed() {
        return armed;
    }

    public LightsLayout layout() {
        return layout;
    }

    /** Absolute positions of the lamps currently lit. */
    public Set<LightsCell> litLamps() {
        return litLamps;
    }

    /** Learned wiring, absolute positions. */
    public Map<LightsCell, Set<LightsCell>> learned() {
        return learned;
    }

    public int revision() {
        return revision;
    }

    /** The current evaluation. */
    public LightsDeviceState state() {
        return state;
    }

    /** Render thread only: visibility flags parallel to {@code state().toFlip()}. */
    public boolean[] visible() {
        return visible;
    }

    public Component label() {
        return label;
    }

    public double labelX() {
        return labelX;
    }

    public double labelY() {
        return labelY;
    }

    public double labelZ() {
        return labelZ;
    }

    /**
     * Whether the optional guard should swallow the player's own click on {@code pos}. Fails open:
     * only with the guard on, the full wiring known, and a non-empty answer that this lever is not in.
     */
    public boolean guardBlocks(BlockPos pos) {
        if (!armed || !ConfigManager.getInstance().get().dungeons.lightsDeviceGuard) {
            return false;
        }
        LightsDeviceState current = state;
        LightsLayout currentLayout = layout;
        if (current.kind() != LightsDeviceState.Kind.SOLVED || current.toFlip().isEmpty() || currentLayout == null) {
            return false;
        }
        LightsCell clicked = cell(pos);
        if (currentLayout.leverIndex(clicked) < 0 || current.toFlip().contains(clicked)) {
            return false;
        }
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Lights] guard ignored a click on lever #{} (not in the answer)",
                currentLayout.leverIndex(clicked));
        return true;
    }

    /** Called every client tick. */
    public void onClientTick() {
        if (!enabled()) {
            if (armed || box != null) {
                reset();
            }
            armed = false;
            return;
        }
        armed = true;
        if (!storeLoaded) {
            storeLoaded = true;
            STORE.load();
        }
        tick++;
        LightsFlipAttributor.Result result = attributor.tick(tick);
        if (result != null) {
            onResult(result);
        }
        boolean scanned = false;
        if (tick - lastScanTick >= SCAN_INTERVAL_TICKS) {
            lastScanTick = tick;
            scan();
            scanned = true;
        }
        if (revision != evaluatedRevision) {
            evaluatedRevision = revision;
            evaluate();
            scanned = true;
        }
        if (scanned) {
            updateVisibility();
        }
    }

    private void evaluate() {
        LightsLayout current = layout;
        LightsWiringData data = STORE.get();
        LightsWiring wiring = current == null ? null
                : LightsWiring.resolve(current, learned, data == null ? null : data.devices);
        state = LightsDeviceState.evaluate(current, litLamps, wiring);
        if (current != null) {
            double x = 0;
            double z = 0;
            int maxY = Integer.MIN_VALUE;
            for (LightsCell lever : current.levers()) {
                x += lever.x() + 0.5;
                z += lever.z() + 0.5;
                maxY = Math.max(maxY, lever.y());
            }
            for (LightsCell lamp : current.lamps()) {
                maxY = Math.max(maxY, lamp.y());
            }
            labelX = x / current.levers().size();
            labelY = maxY + 1.6;
            labelZ = z / current.levers().size();
        }
        label = buildLabel(state);
        if (state.kind() != loggedKind || state.toFlip().size() != loggedFlips) {
            loggedKind = state.kind();
            loggedFlips = state.toFlip().size();
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Lights] {}: {} to flip, wiring {}/{} levers ({}{})",
                    state.kind(), state.toFlip().size(), state.known(), state.levers(),
                    wiring == null ? "none" : wiring.source(),
                    wiring == null || wiring.capturedOn().isEmpty() ? ""
                            : ", " + wiring.certainty().displayName() + " " + wiring.capturedOn());
        }
    }

    private static Component buildLabel(LightsDeviceState s) {
        return switch (s.kind()) {
            case NONE -> Component.empty();
            case DONE -> Component.literal("Lights: done");
            case SOLVED -> Component.literal("Lights: flip " + s.toFlip().size()
                    + (s.wiring() != null && s.wiring().source() == LightsWiring.Source.LEARNED ? " (learned)" : ""));
            case PARTIAL -> Component.literal("Lights: flip " + s.toFlip().size() + " (wiring "
                    + s.known() + "/" + s.levers() + ")");
            case RECORDING -> Component.literal("Lights: recording " + s.known() + "/" + s.levers());
            case INCONSISTENT -> Component.literal("Lights: wiring unclear - recording");
        };
    }

    /** One outline ray per lever to flip, from the camera: a lever is drawn only when nothing hides it. */
    private void updateVisibility() {
        List<LightsCell> toFlip = state.toFlip();
        if (visible.length != toFlip.size()) {
            visible = new boolean[toFlip.size()];
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        Player player = minecraft.player;
        if (level == null || player == null) {
            return;
        }
        Vec3 eye = minecraft.gameRenderer.mainCamera().position();
        for (int i = 0; i < toFlip.size(); i++) {
            LightsCell lever = toFlip.get(i);
            Vec3 target = new Vec3(lever.x() + 0.5, lever.y() + 0.5, lever.z() + 0.5);
            BlockHitResult hit = level.clip(new ClipContext(eye, target, ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE, player));
            visible[i] = hit.getType() == HitResult.Type.MISS
                    || (hit.getBlockPos().getX() == lever.x() && hit.getBlockPos().getY() == lever.y()
                    && hit.getBlockPos().getZ() == lever.z());
        }
    }

    /**
     * A server-confirmed block change. Called from the block-update mixin before the level applies
     * it, so {@code before} is still the old state.
     */
    public void onBlockChange(BlockPos pos, BlockState before, BlockState after) {
        if (!armed || box == null || !inBox(pos)) {
            return;
        }
        if (before.getBlock() instanceof LeverBlock && after.getBlock() instanceof LeverBlock) {
            if (before.getValue(LeverBlock.POWERED) != after.getValue(LeverBlock.POWERED)) {
                attributor.onLever(cell(pos), tick);
            }
            return;
        }
        if (before.getBlock() instanceof RedstoneLampBlock && after.getBlock() instanceof RedstoneLampBlock) {
            boolean lit = after.getValue(RedstoneLampBlock.LIT);
            if (before.getValue(RedstoneLampBlock.LIT) != lit) {
                LightsCell lamp = cell(pos);
                attributor.onLamp(lamp, tick);
                if (lit ? litLamps.add(lamp) : litLamps.remove(lamp)) {
                    revision++;
                }
            }
        }
    }

    private void onResult(LightsFlipAttributor.Result result) {
        LightsLayout current = layout;
        switch (result) {
            case LightsFlipAttributor.Flip flip -> {
                Set<LightsCell> previous = learned.put(flip.lever(), flip.lamps());
                if (!flip.lamps().equals(previous)) {
                    revision++;
                }
                if (current != null) {
                    SkyblockSimplifiedSBS.LOGGER.info("[SBS][Lights] flip lever #{} ({}) -> lamps {}{} | lit now {}",
                            current.leverIndex(flip.lever()), flip.lever().minus(current.anchor()),
                            current.describeLamps(current.lampMask(flip.lamps())),
                            previous != null && !previous.equals(flip.lamps()) ? " (differs from earlier flip)" : "",
                            current.describeLamps(current.lampMask(litLamps)));
                }
            }
            case LightsFlipAttributor.Ambiguous ambiguous -> SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Lights] ambiguous window: {} lever(s) {} flipped {}x, lamps {} - not used",
                    ambiguous.levers().size(), relative(ambiguous.levers()), ambiguous.flips(),
                    relative(ambiguous.lamps()));
            case LightsFlipAttributor.Unattributed stray -> SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Lights] lamps changed with no lever: {}", relative(stray.lamps()));
        }
    }

    private void scan() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        Player player = minecraft.player;
        if (level == null || player == null) {
            return;
        }
        if (box != null) {
            double cx = (box[0] + box[3]) * 0.5;
            double cy = (box[1] + box[4]) * 0.5;
            double cz = (box[2] + box[5]) * 0.5;
            if (player.distanceToSqr(cx, cy, cz) > LEAVE_DISTANCE_SQ) {
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Lights] left the device, forgetting it");
                reset();
            }
        }
        int[] area = box;
        if (area == null) {
            BlockPos c = player.blockPosition();
            area = new int[]{c.getX() - RADIUS_XZ, c.getY() - RADIUS_Y, c.getZ() - RADIUS_XZ,
                    c.getX() + RADIUS_XZ, c.getY() + RADIUS_Y, c.getZ() + RADIUS_XZ};
        }
        List<LightsCell> levers = new ArrayList<>();
        List<LightsCell> lamps = new ArrayList<>();
        Set<LightsCell> lit = new HashSet<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = area[0]; x <= area[3]; x++) {
            for (int y = area[1]; y <= area[4]; y++) {
                for (int z = area[2]; z <= area[5]; z++) {
                    BlockState state = level.getBlockState(pos.set(x, y, z));
                    if (state.getBlock() instanceof LeverBlock) {
                        levers.add(new LightsCell(x, y, z));
                    } else if (state.getBlock() instanceof RedstoneLampBlock) {
                        LightsCell lamp = new LightsCell(x, y, z);
                        lamps.add(lamp);
                        if (state.getValue(RedstoneLampBlock.LIT)) {
                            lit.add(lamp);
                        }
                    }
                }
            }
        }
        if (levers.size() < MIN_CELLS || lamps.size() < MIN_CELLS) {
            return; // not near a device (or a device we cannot see all of yet)
        }
        LightsLayout found = LightsLayout.of(levers, lamps);
        if (found == null) {
            return;
        }
        if (!found.equals(layout)) {
            layout = found;
            box = grow(found);
            revision++;
        }
        if (!lit.equals(litLamps)) {
            litLamps.clear();
            litLamps.addAll(lit);
            revision++;
        }
        if (!found.equals(loggedLayout)) {
            loggedLayout = found;
            logLayout(found);
        }
    }

    private static int[] grow(LightsLayout found) {
        int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (List<LightsCell> side : List.of(found.levers(), found.lamps())) {
            for (LightsCell c : side) {
                b[0] = Math.min(b[0], c.x());
                b[1] = Math.min(b[1], c.y());
                b[2] = Math.min(b[2], c.z());
                b[3] = Math.max(b[3], c.x());
                b[4] = Math.max(b[4], c.y());
                b[5] = Math.max(b[5], c.z());
            }
        }
        for (int i = 0; i < 3; i++) {
            b[i] -= MARGIN;
            b[i + 3] += MARGIN;
        }
        return b;
    }

    private boolean inBox(BlockPos pos) {
        int[] b = box;
        return pos.getX() >= b[0] && pos.getX() <= b[3]
                && pos.getY() >= b[1] && pos.getY() <= b[4]
                && pos.getZ() >= b[2] && pos.getZ() <= b[5];
    }

    private void logLayout(LightsLayout found) {
        StringBuilder text = new StringBuilder();
        List<LightsCell> levers = found.levers();
        for (int i = 0; i < levers.size(); i++) {
            text.append("L#").append(i).append('(').append(levers.get(i).minus(found.anchor())).append(") ");
        }
        text.append("| ");
        List<LightsCell> lamps = found.lamps();
        for (int i = 0; i < lamps.size(); i++) {
            text.append('#').append(i).append('(').append(lamps.get(i).minus(found.anchor())).append(')')
                    .append(litLamps.contains(lamps.get(i)) ? "=LIT " : "=off ");
        }
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Lights] layout anchor {}: {} levers, {} lamps ({} lit) | {}",
                found.anchor(), levers.size(), lamps.size(), litLamps.size(), text.toString().trim());
    }

    private String relative(Set<LightsCell> cells) {
        LightsCell anchor = layout == null ? new LightsCell(0, 0, 0) : layout.anchor();
        StringBuilder text = new StringBuilder("[");
        for (LightsCell cell : cells) {
            if (text.length() > 1) {
                text.append(' ');
            }
            text.append(cell.minus(anchor));
        }
        return text.append(']').toString();
    }

    private static LightsCell cell(BlockPos pos) {
        return new LightsCell(pos.getX(), pos.getY(), pos.getZ());
    }

    /** Clears everything; called when the phase ends, the toggle goes off, or the player walks off. */
    public void reset() {
        box = null;
        layout = null;
        loggedLayout = null;
        litLamps.clear();
        learned.clear();
        attributor.reset();
        state = LightsDeviceState.NONE;
        visible = new boolean[0];
        label = Component.empty();
        loggedKind = LightsDeviceState.Kind.NONE;
        loggedFlips = -1;
        revision++;
    }
}
