/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig.MiningHelpersSettings;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.pathfinding.PathRouting;
import sbs.modid.client.core.pathfinding.PathfindingManager;
import sbs.modid.client.core.pathfinding.Waypoint;
import sbs.modid.client.core.pathfinding.WaypointStore;
import sbs.modid.client.helper.map.logic.MapDatabase;
import sbs.modid.client.helper.map.model.IslandMap;
import sbs.modid.client.helper.map.model.MapWarp;
import sbs.modid.client.skills.SkillIslands;
import sbs.modid.client.skills.mining.model.Commission;
import sbs.modid.client.skills.mining.model.CommissionTarget;
import sbs.modid.client.social.chat.logic.SBSChat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Commission Route: marks where each active commission is done and routes to the nearest.
 *
 * <p><b>It publishes waypoints and nothing else.</b> The route, the marker, the beam and the search
 * are the pathfinding module's, unchanged - this class is the layer that answers "where is this
 * commission done" and hands the answers over as a candidate set tagged
 * {@link Waypoint#SOURCE_COMMISSION}. That is what makes "route to the nearest" mean nearest <i>by
 * route</i> rather than by straight line: one multi-goal search over the whole set decides, exactly
 * as it does for Fairy Souls.
 *
 * <p><b>Ambient, not asked for.</b> The route appears because a commission is running. It therefore
 * ranks below every route the player switched on deliberately, never sends a command, never touches
 * movement, and when it cannot place a commission it says so instead of pointing somewhere plausible.
 *
 * <p><b>Two costs, both bounded.</b> An area commission is a map lookup, cached for its whole life. A
 * mob or material commission is a live scan, and it only runs when it is due: the player has moved a
 * meaningful distance, the block that was being routed to stopped being that material
 * ({@link #onBlockChanged}), or the slow refresh came round. Standing still with three area
 * commissions costs one comparison per tick.
 */
public final class CommissionRoute {

    private static final CommissionRoute INSTANCE = new CommissionRoute();

    /** Nothing is recomputed more often than this, whatever else happens. */
    private static final long TICK_INTERVAL_MS = 500L;

    /** A live target is re-scanned at least this often even standing still - the world moves. */
    private static final long LIVE_REFRESH_MS = 8_000L;

    /** Moving this far makes "the nearest one" a different question worth re-asking. */
    private static final double MOVED_FAR = 12.0;

    /** Inside this distance of an area destination the guidance has done its job and stands down. */
    private static final double ARRIVED_RADIUS = 12.0;

    /** How often the same "that area is not loaded, here is the warp" line may be said. */
    private static final long WARP_HINT_INTERVAL_MS = 60_000L;

    /** Resolution per commission name, kept between scans. Rebuilt when the commission set changes. */
    private final Map<String, CommissionTarget> resolved = new LinkedHashMap<>();

    /** Commissions whose area the player has stood in - guidance stops rather than nagging. */
    private final List<String> arrived = new ArrayList<>();

    /** The commission the player pinned by hand, or empty while the nearest is picked for them. */
    private String pinned = "";

    private long lastTickAt;
    private long lastLiveScanAt;
    private long lastWarpHintAt;
    private BlockPos lastScanFrom;
    private String lastIsland = "";
    private boolean liveDirty = true;
    private boolean published;

    private CommissionRoute() {
    }

    public static CommissionRoute getInstance() {
        return INSTANCE;
    }

    private static MiningHelpersSettings cfg() {
        return ConfigManager.getInstance().get().miningHelpers;
    }

    // ------------------------------------------------------------------ tick

    public void tick(Minecraft minecraft) {
        if (minecraft == null || !PathRouting.commissionRouting()) {
            clearPublished();
            return;
        }
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null) {
            clearPublished();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastTickAt < TICK_INTERVAL_MS) {
            return;
        }
        lastTickAt = now;

        // Island change is a hard reset: commission coordinates belong to one island's coordinate
        // space, and a marker surviving the boundary points at an arbitrary spot on the next one.
        String island = SkyBlockLocation.island();
        if (!island.equals(lastIsland)) {
            lastIsland = island;
            forget();
        }
        if (!SkillIslands.miningAllowed()) {
            clearPublished();
            return;
        }

        List<Commission> open = openCommissions();
        if (open.isEmpty()) {
            clearPublished();
            return;
        }
        forgetStale(open);

        BlockPos from = player.blockPosition();
        boolean rescanLive = liveDirty
                || lastScanFrom == null
                || Math.sqrt(lastScanFrom.distSqr(from)) > MOVED_FAR
                || now - lastLiveScanAt > LIVE_REFRESH_MS;
        if (rescanLive) {
            liveDirty = false;
            lastLiveScanAt = now;
            lastScanFrom = from;
        }

        List<Waypoint> waypoints = new ArrayList<>(open.size());
        for (Commission commission : open) {
            CommissionTarget target = targetFor(commission, level, from, rescanLive);
            if (target.kind() == CommissionTarget.Kind.UNRESOLVED || !target.hasPosition()) {
                continue;
            }
            if (target.kind() == CommissionTarget.Kind.AREA && hasArrived(target, from)) {
                continue;
            }
            waypoints.add(waypointFor(commission, target, level, now));
        }
        if (!pinned.isEmpty()) {
            waypoints.removeIf(waypoint -> !waypoint.name.startsWith(pinned));
        } else if (!cfg().commissionRouteAuto) {
            // Manual selection with nothing chosen yet: the places are still worth seeing, but
            // nothing is routed to until the player says which. Marking them unroutable is how that
            // is said - the same flag an off-island marker uses, and the search skips them.
            for (Waypoint waypoint : waypoints) {
                waypoint.routable = false;
            }
        }
        WaypointStore.setTransient(Waypoint.SOURCE_COMMISSION, waypoints);
        published = !waypoints.isEmpty();
    }

    /** The commissions worth guiding to: everything the tab reports that is not finished. */
    private static List<Commission> openCommissions() {
        MiningTracker tracker = MiningTracker.getInstance();
        if (!tracker.commissionsFresh()) {
            return List.of();
        }
        List<Commission> open = new ArrayList<>(tracker.commissions().size());
        for (Commission commission : tracker.commissions()) {
            if (!commission.done()) {
                open.add(commission);
            }
        }
        return open;
    }

    /**
     * The cached resolution, re-running the scan only for the live kinds and only when due. An area
     * is resolved once: its answer cannot change while the commission lasts.
     */
    private CommissionTarget targetFor(Commission commission, ClientLevel level, BlockPos from,
                                       boolean rescanLive) {
        CommissionTarget known = resolved.get(commission.name());
        if (known != null && !known.isLive() && known.kind() != CommissionTarget.Kind.UNRESOLVED) {
            return known;
        }
        if (known != null && known.isLive() && !rescanLive) {
            return known;
        }
        CommissionTarget fresh = CommissionTargets.resolve(commission.name(), level, from);
        CommissionTarget previous = resolved.put(commission.name(), fresh);
        if (previous == null || previous.kind() != fresh.kind()) {
            logResolution(commission, fresh);
        }
        return fresh;
    }

    /**
     * The waypoint for a resolved commission.
     *
     * <p>An unloaded destination is published {@linkplain Waypoint#routable unroutable}: the marker
     * still hangs on the horizon with its distance, which is the useful half, while the search is not
     * sent into chunks the client does not have and cannot read. Where the map data names a warp for
     * that place, the player is told about it once - and told, never sent, because a command this mod
     * runs by itself is a command the player did not choose to run.
     */
    private Waypoint waypointFor(Commission commission, CommissionTarget target, ClientLevel level,
                                 long now) {
        String label = commission.name() + " §7" + commission.progressLabel();
        Waypoint waypoint = new Waypoint(label, target.pos(), WaypointStore.currentDimension(),
                Waypoint.SOURCE_COMMISSION);
        BlockPos pos = target.pos();
        boolean loaded = level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4);
        waypoint.routable = loaded;
        if (!loaded) {
            waypoint.name = label + " §8(not loaded)";
            offerWarp(target, now);
        }
        return waypoint;
    }

    /** Tells the player once a minute which warp lands near an area that is too far to route to. */
    private void offerWarp(CommissionTarget target, long now) {
        if (target.warpId().isEmpty() || now - lastWarpHintAt < WARP_HINT_INTERVAL_MS) {
            return;
        }
        IslandMap map = MapDatabase.current();
        MapWarp warp = map == null ? null : map.warpById(target.warpId());
        if (warp == null || warp.command == null || warp.command.isBlank()) {
            return;
        }
        lastWarpHintAt = now;
        String place = target.token().isEmpty() ? target.label() : target.token();
        SBSChat.send(Component.literal(" §7" + place + " is not loaded yet - §f"
                + warp.command + "§7 lands nearby. The route draws itself once you are there."));
    }

    /** Whether the player has been inside an area destination, which is where guidance ends. */
    private boolean hasArrived(CommissionTarget target, BlockPos from) {
        if (arrived.contains(target.label())) {
            return true;
        }
        if (Math.sqrt(target.pos().distSqr(from)) <= ARRIVED_RADIUS) {
            arrived.add(target.label());
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ invalidation

    /**
     * A block the world just changed. Only interesting when it is the very block a material
     * commission is routed to: the vein being mined out is exactly the moment "the nearest one" has a
     * new answer, and noticing it here turns a stale route into an immediate re-scan instead of a
     * marker hanging over an empty hole until the refresh comes round.
     */
    public void onBlockChanged(BlockPos pos, BlockState before, BlockState after) {
        if (pos == null || liveDirty || !published || resolved.isEmpty()) {
            return;
        }
        for (CommissionTarget target : resolved.values()) {
            if (target.kind() == CommissionTarget.Kind.MATERIAL && pos.equals(target.pos())) {
                liveDirty = true;
                return;
            }
        }
    }

    /** Drops every resolution and marker - island change, or the feature being switched off. */
    private void forget() {
        resolved.clear();
        arrived.clear();
        pinned = "";
        lastScanFrom = null;
        liveDirty = true;
        clearPublished();
    }

    /** Forgets resolutions for commissions that are no longer running, so the map cannot grow. */
    private void forgetStale(List<Commission> open) {
        List<String> names = new ArrayList<>(open.size());
        for (Commission commission : open) {
            names.add(commission.name());
        }
        resolved.keySet().removeIf(name -> !names.contains(name));
        arrived.removeIf(label -> {
            for (CommissionTarget target : resolved.values()) {
                if (label.equals(target.label())) {
                    return false;
                }
            }
            return true;
        });
        if (!pinned.isEmpty() && !names.contains(pinned)) {
            pinned = "";   // the pinned commission finished: back to picking the nearest
        }
    }

    private void clearPublished() {
        if (published) {
            WaypointStore.clearTransient(Waypoint.SOURCE_COMMISSION);
            published = false;
        }
    }

    // ------------------------------------------------------------------ player actions

    /**
     * Keybind: pin the route to the next commission in the list, or unpin on the last press.
     *
     * <p>A pin is what "manual" means in practice - it survives until the commission finishes, the
     * island changes or the player cycles past the end of the list.
     */
    public void cycle() {
        if (!PathRouting.commissionRouting()) {
            return;
        }
        List<Commission> open = openCommissions();
        if (open.isEmpty()) {
            SBSChat.send(Component.literal(" §7No commission is running."));
            return;
        }
        int next = 0;
        if (!pinned.isEmpty()) {
            for (int i = 0; i < open.size(); i++) {
                if (open.get(i).name().equals(pinned)) {
                    next = i + 1;
                    break;
                }
            }
        }
        if (next >= open.size()) {
            pinned = "";
            SBSChat.send(Component.literal(" §7Route follows the nearest commission again."));
        } else {
            pinned = open.get(next).name();
            CommissionTarget target = resolved.get(pinned);
            String where = target == null || target.kind() == CommissionTarget.Kind.UNRESOLVED
                    ? " §8(nowhere known to send you)" : "";
            SBSChat.send(Component.literal(" §7Routing to §f" + pinned + where));
        }
        PathfindingManager.getInstance().invalidate();
    }

    /** {@code /sbs commission} - every commission with what was made of it. */
    public void listToChat() {
        List<Commission> open = openCommissions();
        if (!MiningTracker.getInstance().commissionsFresh()) {
            SBSChat.send(Component.literal(" §7The tab list is not serving commissions right now"
                    + " §8(" + SkyBlockLocation.describe() + ")"));
            return;
        }
        SBSChat.send(Component.literal(" §bCommissions"));
        for (Commission commission : MiningTracker.getInstance().commissions()) {
            CommissionTarget target = resolved.get(commission.name());
            String state;
            if (commission.done()) {
                state = "§adone";
            } else if (target == null) {
                state = "§7not looked at yet";
            } else if (target.kind() == CommissionTarget.Kind.UNRESOLVED) {
                state = "§cnowhere known" + (target.token().isEmpty() ? ""
                        : " §8(unmatched: " + target.token() + ")");
            } else {
                state = "§f" + target.describe();
            }
            SBSChat.send(Component.literal(" §8- §f" + commission.name() + " §7"
                    + commission.progressLabel() + " §8| " + state));
        }
        if (!open.isEmpty() && CommissionBlocks.isEmpty()) {
            SBSChat.send(Component.literal(" §8No materials are known yet - §f/sbs commission blocks"
                    + "§8 lists what is around you, and " + CommissionBlocks.file().getFileName()
                    + " is where it goes."));
        }
    }

    /**
     * {@code /sbs commission blocks} - the distinct block ids around the player.
     *
     * <p>The capture aid for {@link CommissionBlocks}: a material's blocks have to be observed before
     * they can be matched, and this is the observation, taken from the player's own client while they
     * stand in the place the commission is about.
     */
    public void blocksToChat() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null) {
            return;
        }
        BlockPos from = player.blockPosition();
        TreeSet<String> ids = new TreeSet<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -6; dx <= 6; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -6; dz <= 6; dz++) {
                    cursor.set(from.getX() + dx, from.getY() + dy, from.getZ() + dz);
                    BlockState state = level.getBlockState(cursor);
                    if (!state.isAir()) {
                        ids.add(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath()
                                .toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        SBSChat.send(Component.literal(" §bBlocks within 6 blocks §7(" + ids.size() + ")"));
        SBSChat.send(Component.literal(" §7" + String.join("§8, §7", ids)));
        SBSChat.send(Component.literal(" §8Put the ones that are the commission's material into "
                + CommissionBlocks.file()));
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Commission] blocks around {}: {}", from, ids);
    }

    /** Reloads the material file after it has been edited by hand. */
    public void reloadBlocks() {
        CommissionBlocks.load();
        liveDirty = true;
        SBSChat.send(Component.literal(CommissionBlocks.isEmpty()
                ? " §7No materials are defined in " + CommissionBlocks.file().getFileName()
                : " §aMaterial list reloaded."));
    }

    /** Whether a commission has somewhere to be sent - what the HUD row marks. */
    public boolean isUnresolved(String commissionName) {
        CommissionTarget target = resolved.get(commissionName);
        return target != null && target.kind() == CommissionTarget.Kind.UNRESOLVED;
    }

    /** Whether this commission is the one the route is pinned to. */
    public boolean isPinned(String commissionName) {
        return !pinned.isEmpty() && pinned.equals(commissionName);
    }

    /**
     * One line per commission the first time it resolves, or whenever its kind changes.
     *
     * <p>The unresolved case is the one this exists for: it carries the exact commission text and the
     * words that matched nothing, which is what turns "no waypoint appeared" into a vocabulary gap
     * somebody can close.
     */
    private static void logResolution(Commission commission, CommissionTarget target) {
        if (target.kind() == CommissionTarget.Kind.UNRESOLVED) {
            SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Commission] no destination for \"{}\" (unmatched words: {}) at {}",
                    commission.name(), target.token(), SkyBlockLocation.describe());
            return;
        }
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Commission] {}", target.describe());
    }
}
