/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.secretroutes.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.dev.RoomRotation;
import sbs.modid.client.dungeons.run.logic.DungeonRoomTracker;
import sbs.modid.client.dungeons.run.logic.DungeonRoomMatcher.RoomMatch;
import sbs.modid.client.dungeons.run.logic.CollectedSecrets;
import sbs.modid.client.dungeons.events.DungeonEvents;
import sbs.modid.client.dungeons.secretroutes.model.SecretRoute;
import sbs.modid.client.dungeons.secretroutes.model.SecretWaypoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The brain of the Secret Routes module: binds routes to the recognised dungeon room, records the
 * walk while scanning, places the four waypoint types at the player, feeds the Dungeon Breaker scan,
 * and resolves stored (canonical, room-relative) data back into world space for the renderer.
 *
 * <p><b>Room binding.</b> Everything hangs off {@link DungeonRoomTracker#activeMatch()} - the room's
 * name, generated {@code facing} and anchor. Recording stores positions in the canonical NORTH frame
 * via {@link RoomRotation}; rendering transforms them back with the CURRENT run's facing, so a route
 * recorded once shows correctly however the room is rotated this run. With no identified room the add
 * actions refuse and say so (never a silent failure).
 *
 * <p>Runs entirely on the client thread ({@link #tick} from the tracking mixin and the render pass do
 * not overlap), so no locking is needed.
 */
public final class SecretRoutesManager {

    private static final SecretRoutesManager INSTANCE = new SecretRoutesManager();

    /** A point counts as "passed" once the player has been within this many blocks of it. */
    private static final double PASS_RADIUS = 2.2;

    /** A resolved waypoint ready to draw: the source, its world position, and whether it was passed. */
    public record RenderWaypoint(SecretWaypoint source, Vec3 world, boolean passed) {
    }

    /** A resolved breaker block: world position, its break order, and the block id. */
    public record RenderBreaker(Vec3 world, int order, String blockId) {
    }

    private boolean scanning;
    private boolean breakerScanning;

    /** The route being recorded into while a scan/breaker-scan runs (bound at start). */
    private SecretRoute recordingRoute;
    /** The room name the recording is bound to; guards against sampling in the wrong room. */
    private String recordingRoom;
    private BlockPos lastTrailBlock;

    /** A collected secret waypoint counts near a chest/lever/pickup within this many blocks. */
    private static final double COLLECT_RADIUS_SQR = 30.0;

    /** Points already walked past this visit, by identity (for the dim option); reset on room change. */
    private final Set<SecretWaypoint> passed = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private String passedRoom;

    private boolean containerWasOpen;

    private SecretRoutesManager() {
        CollectedSecrets.getInstance().addCandidateSource(this::uncollectedSecrets);
    }

    public static SecretRoutesManager getInstance() {
        return INSTANCE;
    }

    public static SBSConfig.SecretRoutesSettings cfg() {
        return ConfigManager.getInstance().get().secretRoutes;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    // ------------------------------------------------------------------
    // Room binding
    // ------------------------------------------------------------------

    /** The identified room this run ({@code null} = no room detected → add actions are locked). */
    public RoomMatch boundRoom() {
        return DungeonRoomTracker.getInstance().activeMatch();
    }

    /** The identified room's name, or {@code null}. */
    public String boundRoomName() {
        RoomMatch match = boundRoom();
        return match != null ? match.name() : null;
    }

    public boolean hasRoom() {
        return boundRoom() != null;
    }

    // ------------------------------------------------------------------
    // Route selection (within the current room)
    // ------------------------------------------------------------------

    /** Every saved route for the current room (empty when none / no room). */
    public List<SecretRoute> routesForCurrentRoom() {
        return new ArrayList<>(SecretRouteStore.routesFor(boundRoomName()));
    }

    /** The selected route in the current room, or {@code null} when no room / no routes exist. */
    public SecretRoute selectedRoute() {
        String room = boundRoomName();
        if (room == null) {
            return null;
        }
        List<SecretRoute> routes = SecretRouteStore.routesFor(room);
        if (routes.isEmpty()) {
            return null;
        }
        int index = Math.max(0, Math.min(cfg().selectedRoute, routes.size() - 1));
        cfg().selectedRoute = index;
        return routes.get(index);
    }

    public void selectRoute(int index) {
        cfg().selectedRoute = Math.max(0, index);
        save();
    }

    /** Creates a new route in the current room and selects it; {@code null} if no room. */
    public SecretRoute createRoute(String name) {
        String room = boundRoomName();
        if (room == null) {
            return null;
        }
        SecretRoute route = SecretRouteStore.createRoute(room, name);
        cfg().selectedRoute = SecretRouteStore.routesFor(room).size() - 1;
        save();
        return route;
    }

    public void deleteSelectedRoute() {
        String room = boundRoomName();
        SecretRoute route = selectedRoute();
        if (room != null && route != null) {
            SecretRouteStore.removeRoute(room, route);
            cfg().selectedRoute = 0;
            save();
        }
    }

    /** The route new points are appended to, created on demand when the room has none yet. */
    private SecretRoute ensureRoute() {
        SecretRoute route = selectedRoute();
        return route != null ? route : createRoute("Route 1");
    }

    // ------------------------------------------------------------------
    // Recording (walk trail + breaker scan)
    // ------------------------------------------------------------------

    public boolean isScanning() {
        return scanning;
    }

    public boolean isBreakerScanning() {
        return breakerScanning;
    }

    /** Starts recording the walk path into the selected route. Requires a detected room. */
    public boolean startScan() {
        if (!requireRoom()) {
            return false;
        }
        recordingRoute = ensureRoute();
        recordingRoom = boundRoomName();
        lastTrailBlock = null;
        scanning = true;
        overlay("§aScan started — walk the route");
        return true;
    }

    /** Stops recording the walk path and persists. */
    public void stopScan() {
        scanning = false;
        recordingRoute = null;
        recordingRoom = null;
        lastTrailBlock = null;
        SecretRouteStore.save();
        overlay("§7Scan stopped");
    }

    /** Toggles the Dungeon Breaker scan: logs broken blocks + walk path into the selected route. */
    public boolean setBreakerScan(boolean on) {
        if (on) {
            if (!requireRoom()) {
                return false;
            }
            recordingRoute = ensureRoute();
            recordingRoom = boundRoomName();
            lastTrailBlock = null;
            breakerScanning = true;
            overlay("§aBreaker scan on — break blocks in order");
        } else {
            breakerScanning = false;
            if (!scanning) {
                recordingRoute = null;
                recordingRoom = null;
            }
            SecretRouteStore.save();
            overlay("§7Breaker scan off");
        }
        return true;
    }

    /** Called once per client tick from the tracking mixin. */
    public void tick(Minecraft minecraft) {
        SBSConfig.SecretRoutesSettings cfg = cfg();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null || !cfg.enabled) {
            return;
        }
        // A room change clears the walked-past memory so a route re-dims from scratch on re-entry.
        // Collected secrets are NOT cleared: they live in CollectedSecrets for the whole run.
        String room = boundRoomName();
        if (!java.util.Objects.equals(room, passedRoom)) {
            passed.clear();
            passedRoom = room;
        }
        if ((scanning || breakerScanning) && recordingRoute != null) {
            sampleTrail(player);
        }
        if (cfg.dimPassed && room != null) {
            markPassed(player);
        }
        if (room != null) {
            detectCollected(minecraft, player);
        }
    }

    /**
     * Detects a secret being collected at one of this route's points - a lever pulled at a LEVER
     * point, a chest opened next to a CHEST point - and reports it through
     * {@link DungeonEvents#fireSecretFound}. Items, bats and essences (the secret counters) are
     * {@link CollectedSecrets}' job, and so is hiding: the render asks it, not a list of our own.
     */
    private void detectCollected(Minecraft minecraft, LocalPlayer player) {
        SecretRoute route = selectedRoute();
        RoomMatch match = boundRoom();
        if (route == null || match == null || minecraft.level == null) {
            return;
        }
        Vec3 pos = player.position();

        // Lever pulled: a powered lever standing at the waypoint block.
        for (SecretWaypoint waypoint : route.waypoints) {
            if (collectable(waypoint, match) && waypoint.subtype == SecretWaypoint.Secret.LEVER) {
                BlockPos world = blockOf(match, waypoint);
                BlockState state = minecraft.level.getBlockState(world);
                if (state.getBlock() == Blocks.LEVER && state.getValue(BlockStateProperties.POWERED)) {
                    DungeonEvents.fireSecretFound(new DungeonEvents.Secret("lever", world));
                }
            }
        }

        // Chest opened: the nearest un-collected chest point when a container screen just opened.
        boolean containerOpen = GuiStateManager.getInstance().getCurrentScreen() instanceof AbstractContainerScreen;
        if (containerOpen && !containerWasOpen) {
            SecretWaypoint nearest = nearestCollectable(route, match, pos, SecretWaypoint.Secret.CHEST);
            if (nearest != null) {
                DungeonEvents.fireSecretFound(new DungeonEvents.Secret("chest", blockOf(match, nearest)));
            }
        }
        containerWasOpen = containerOpen;
    }

    /** An enabled SECRET-type waypoint that {@link CollectedSecrets} does not hold yet. */
    private boolean collectable(SecretWaypoint waypoint, RoomMatch match) {
        return waypoint.enabled && waypoint.type == SecretWaypoint.Type.SECRET
                && !CollectedSecrets.getInstance().isCollected(match.name(), blockOf(match, waypoint));
    }

    /** The world block of a stored point under this run's rotation. */
    private static BlockPos blockOf(RoomMatch match, SecretWaypoint waypoint) {
        return RoomRotation.relativeToActual(match.facing(), match.anchor(),
                waypoint.relX, waypoint.relY, waypoint.relZ);
    }

    /** The selected route's uncollected secret points, for the counter path. */
    private List<BlockPos> uncollectedSecrets() {
        SecretRoute route = selectedRoute();
        RoomMatch match = boundRoom();
        List<BlockPos> out = new ArrayList<>();
        if (route == null || match == null || !cfg().enabled) {
            return out;
        }
        for (SecretWaypoint waypoint : route.waypoints) {
            if (collectable(waypoint, match)) {
                out.add(blockOf(match, waypoint));
            }
        }
        return out;
    }

    /** Nearest collectable secret point of one subtype within the collect radius. */
    private SecretWaypoint nearestCollectable(SecretRoute route, RoomMatch match, Vec3 pos,
                                              SecretWaypoint.Secret subtype) {
        SecretWaypoint best = null;
        double bestDistance = COLLECT_RADIUS_SQR;
        for (SecretWaypoint waypoint : route.waypoints) {
            if (!collectable(waypoint, match) || waypoint.subtype != subtype) {
                continue;
            }
            Vec3 world = worldOf(match, waypoint);
            double distance = pos.distanceToSqr(world.x, world.y, world.z);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = waypoint;
            }
        }
        return best;
    }

    /** Appends the player's current feet block to the recording trail (canonical, deduped). */
    private void sampleTrail(LocalPlayer player) {
        RoomMatch match = boundRoom();
        if (match == null || !java.util.Objects.equals(boundRoomName(), recordingRoom)) {
            return; // only sample while still in the room the recording was bound to
        }
        BlockPos feet = player.blockPosition();
        if (feet.equals(lastTrailBlock)) {
            return;
        }
        lastTrailBlock = feet;
        BlockPos rel = RoomRotation.actualToRelative(match.facing(), match.anchor(), feet);
        List<int[]> trail = recordingRoute.trail;
        int[] last = trail.isEmpty() ? null : trail.get(trail.size() - 1);
        if (last == null || last[0] != rel.getX() || last[1] != rel.getY() || last[2] != rel.getZ()) {
            trail.add(new int[] {rel.getX(), rel.getY(), rel.getZ()});
        }
    }

    /** Marks any enabled waypoint the player is currently near as passed (sticky until room change). */
    private void markPassed(LocalPlayer player) {
        SecretRoute route = selectedRoute();
        if (route == null) {
            return;
        }
        RoomMatch match = boundRoom();
        if (match == null) {
            return;
        }
        Vec3 pos = player.position();
        for (SecretWaypoint waypoint : route.waypoints) {
            if (!waypoint.enabled || passed.contains(waypoint)) {
                continue;
            }
            Vec3 world = worldOf(match, waypoint);
            if (pos.distanceToSqr(world.x, world.y, world.z) <= PASS_RADIUS * PASS_RADIUS) {
                passed.add(waypoint);
            }
        }
    }

    // ------------------------------------------------------------------
    // Waypoint placement (all at the player, canonical-stored)
    // ------------------------------------------------------------------

    /** A plain standing waypoint on the block under the player's feet. */
    public void addStanding() {
        RoomMatch match = requireRoomMatch();
        if (match == null) {
            return;
        }
        SecretWaypoint waypoint = baseAtFeet(match);
        waypoint.type = SecretWaypoint.Type.STANDING;
        append(waypoint, "Standing waypoint");
    }

    /** The secret subtype currently chosen for "Scan Items" (config-backed, shared with the editor). */
    public SecretWaypoint.Secret currentSubtype() {
        SecretWaypoint.Secret[] values = SecretWaypoint.Secret.values();
        int index = Math.floorMod(cfg().scanSubtype, values.length);
        return values[index];
    }

    /** Cycles the "Scan Items" subtype to the next value and persists. */
    public void cycleSubtype() {
        cfg().scanSubtype = Math.floorMod(cfg().scanSubtype + 1, SecretWaypoint.Secret.values().length);
        save();
    }

    /** "Scan Items" placed at the player's feet with the currently chosen subtype (keybind + button). */
    public void scanItemsAtFeet() {
        addSecretAtFeet(currentSubtype());
    }

    /** A secret/item waypoint on the block under the player's feet, of the given subtype. */
    public void addSecretAtFeet(SecretWaypoint.Secret subtype) {
        RoomMatch match = requireRoomMatch();
        if (match == null) {
            return;
        }
        SecretWaypoint waypoint = baseAtFeet(match);
        waypoint.type = SecretWaypoint.Type.SECRET;
        waypoint.subtype = subtype;
        append(waypoint, (subtype != null ? subtype.name() : "Secret") + " waypoint");
    }

    /** An etherwarp (AOTV) waypoint: precise stand + look + the block in the crosshair as the target. */
    public void addAotv() {
        Minecraft minecraft = Minecraft.getInstance();
        RoomMatch match = requireRoomMatch();
        LocalPlayer player = minecraft.player;
        if (match == null || player == null) {
            return;
        }
        HitResult hit = minecraft.hitResult;
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
            overlay("§cLook at the etherwarp target block first");
            return;
        }
        SecretWaypoint waypoint = baseAtFeet(match);
        waypoint.type = SecretWaypoint.Type.AOTV_WARP;
        applyPrecise(waypoint, match, player);
        BlockPos targetRel = RoomRotation.actualToRelative(match.facing(), match.anchor(), blockHit.getBlockPos());
        waypoint.targetRelX = targetRel.getX();
        waypoint.targetRelY = targetRel.getY();
        waypoint.targetRelZ = targetRel.getZ();
        append(waypoint, "AOTV warp");
    }

    /** A pearl-throw waypoint: precise stand + pixel-exact look, no target block. */
    public void addPearl() {
        Minecraft minecraft = Minecraft.getInstance();
        RoomMatch match = requireRoomMatch();
        LocalPlayer player = minecraft.player;
        if (match == null || player == null) {
            return;
        }
        SecretWaypoint waypoint = baseAtFeet(match);
        waypoint.type = SecretWaypoint.Type.PEARL;
        applyPrecise(waypoint, match, player);
        append(waypoint, "Pearl throw");
    }

    /** Fills the canonical block position (feet) shared by every waypoint type. */
    private static SecretWaypoint baseAtFeet(RoomMatch match) {
        LocalPlayer player = Minecraft.getInstance().player;
        SecretWaypoint waypoint = new SecretWaypoint();
        BlockPos rel = RoomRotation.actualToRelative(match.facing(), match.anchor(), player.blockPosition());
        waypoint.relX = rel.getX();
        waypoint.relY = rel.getY();
        waypoint.relZ = rel.getZ();
        return waypoint;
    }

    /** Adds the pixel-exact precise stand position + canonical yaw/pitch (never rounded). */
    private static void applyPrecise(SecretWaypoint waypoint, RoomMatch match, LocalPlayer player) {
        Vec3 rel = RoomRotation.actualToRelative(match.facing(), match.anchor(),
                player.getX(), player.getY(), player.getZ());
        waypoint.preciseX = rel.x;
        waypoint.preciseY = rel.y;
        waypoint.preciseZ = rel.z;
        waypoint.yaw = RoomRotation.yawToRelative(match.facing(), player.getYRot());
        waypoint.pitch = player.getXRot();
    }

    /**
     * Appends the waypoint to the selected route and persists. Deliberately does NOT pop a text
     * dialog: the shared {@code NameInputScreen} closes the game screen and forbids empty text, so
     * descriptions are set inline in {@code SecretRoutesScreen} instead - this keeps both the keybind
     * flow (place silently, describe later) and the screen flow (place, edit its row) working.
     */
    private void append(SecretWaypoint waypoint, String what) {
        SecretRoute route = ensureRoute();
        waypoint.index = route.nextIndex();
        route.waypoints.add(waypoint);
        SecretRouteStore.save();
        overlay("§a" + what + " added — describe it in Secret Routes");
    }

    /** Sets a waypoint's description and persists (called by the editor). */
    public void setDescription(SecretWaypoint waypoint, String description) {
        waypoint.description = description == null ? "" : description.trim();
        SecretRouteStore.save();
    }

    public void deleteWaypoint(SecretWaypoint waypoint) {
        SecretRoute route = selectedRoute();
        if (route != null && route.waypoints.remove(waypoint)) {
            route.renumber();
            SecretRouteStore.save();
        }
    }

    public void toggleWaypoint(SecretWaypoint waypoint) {
        waypoint.enabled = !waypoint.enabled;
        SecretRouteStore.save();
    }

    // ------------------------------------------------------------------
    // Dungeon Breaker scan feed (from the block-break mixin)
    // ------------------------------------------------------------------

    /** Logs a broken block into the recording route (no-op unless the breaker scan is running). */
    public void onBlockBroken(BlockPos worldPos, BlockState state) {
        if (!breakerScanning || recordingRoute == null) {
            return;
        }
        RoomMatch match = boundRoom();
        if (match == null || !java.util.Objects.equals(boundRoomName(), recordingRoom)) {
            return;
        }
        BlockPos rel = RoomRotation.actualToRelative(match.facing(), match.anchor(), worldPos);
        SecretRoute.BreakerBlock block = new SecretRoute.BreakerBlock();
        block.relX = rel.getX();
        block.relY = rel.getY();
        block.relZ = rel.getZ();
        block.blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        block.order = recordingRoute.breakerBlocks.size();
        block.timestamp = System.currentTimeMillis();
        recordingRoute.breakerBlocks.add(block);
    }

    // ------------------------------------------------------------------
    // Render resolution (canonical → world, for the current run's rotation)
    // ------------------------------------------------------------------

    /** The selected route's waypoints resolved to world space, or empty when nothing to draw. */
    public List<RenderWaypoint> renderWaypoints() {
        SecretRoute route = selectedRoute();
        RoomMatch match = boundRoom();
        if (route == null || match == null) {
            return List.of();
        }
        List<RenderWaypoint> out = new ArrayList<>(route.waypoints.size());
        for (SecretWaypoint waypoint : route.waypoints) {
            if (!waypoint.enabled || (waypoint.type == SecretWaypoint.Type.SECRET
                    && CollectedSecrets.getInstance().isCollected(match.name(), blockOf(match, waypoint)))) {
                continue; // collected secrets are hidden for the rest of the run (Secret-Clicked coupling)
            }
            out.add(new RenderWaypoint(waypoint, worldOf(match, waypoint), passed.contains(waypoint)));
        }
        return out;
    }

    /** The recorded trail resolved to world block-centre points (empty when none). */
    public List<Vec3> renderTrail() {
        SecretRoute route = selectedRoute();
        RoomMatch match = boundRoom();
        if (route == null || match == null || route.trail.isEmpty()) {
            return List.of();
        }
        List<Vec3> out = new ArrayList<>(route.trail.size());
        for (int[] point : route.trail) {
            Vec3 world = RoomRotation.relativeToActual(match.facing(), match.anchor(),
                    point[0] + 0.5, point[1], point[2] + 0.5);
            out.add(world);
        }
        return out;
    }

    /** The breaker blocks resolved to world space (empty when none). */
    public List<RenderBreaker> renderBreakers() {
        SecretRoute route = selectedRoute();
        RoomMatch match = boundRoom();
        if (route == null || match == null || route.breakerBlocks.isEmpty()) {
            return List.of();
        }
        List<RenderBreaker> out = new ArrayList<>(route.breakerBlocks.size());
        for (SecretRoute.BreakerBlock block : route.breakerBlocks) {
            Vec3 world = RoomRotation.relativeToActual(match.facing(), match.anchor(),
                    (double) block.relX, (double) block.relY, (double) block.relZ);
            out.add(new RenderBreaker(world, block.order, block.blockId));
        }
        return out;
    }

    /** The world block position of a waypoint's anchor block for the current room facing. */
    private static Vec3 worldOf(RoomMatch match, SecretWaypoint waypoint) {
        BlockPos world = RoomRotation.relativeToActual(match.facing(), match.anchor(),
                waypoint.relX, waypoint.relY, waypoint.relZ);
        return new Vec3(world.getX(), world.getY(), world.getZ());
    }

    /** The world yaw/pitch a pearl/AOTV waypoint should be aimed at this run, or {@code null}. */
    public float[] worldAim(SecretWaypoint waypoint) {
        RoomMatch match = boundRoom();
        if (match == null || !waypoint.hasAim()) {
            return null;
        }
        return new float[] {RoomRotation.yawToActual(match.facing(), waypoint.yaw), waypoint.pitch};
    }

    // ------------------------------------------------------------------
    // Gating helpers
    // ------------------------------------------------------------------

    private boolean requireRoom() {
        if (!hasRoom()) {
            overlay("§cNo room detected — stand in a recognised room first");
            return false;
        }
        return true;
    }

    private RoomMatch requireRoomMatch() {
        RoomMatch match = boundRoom();
        if (match == null) {
            overlay("§cNo room detected — stand in a recognised room first");
        }
        return match;
    }

    private static void overlay(String message) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.sendOverlayMessage(Component.literal(message));
        }
    }
}
