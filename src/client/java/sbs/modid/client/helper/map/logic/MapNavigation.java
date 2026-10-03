/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.map.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.command.SBSCommands;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.pathfinding.PathfindingManager;
import sbs.modid.client.core.pathfinding.JumpPad;
import sbs.modid.client.core.pathfinding.JumpPads;
import sbs.modid.client.core.pathfinding.Waypoint;
import sbs.modid.client.core.pathfinding.WaypointStore;
import sbs.modid.client.helper.map.model.IslandMap;
import sbs.modid.client.helper.map.model.MapLocation;
import sbs.modid.client.helper.map.model.MapWarp;
import sbs.modid.client.helper.warp.WarpAvailability;
import sbs.modid.client.social.chat.logic.SBSChat;

import java.util.List;
import java.util.Locale;

/**
 * Takes you to a place you clicked on the map: warp as far as a warp helps, then mark the spot and
 * draw the route for the walk.
 *
 * <p><b>Visual guidance only.</b> This class sends warp commands - the same ones you could type - and
 * publishes a waypoint for the existing pathfinding renderer. It never touches movement input. Walking
 * the drawn route is the player's job, deliberately: automated movement is a macro and against
 * Hypixel's rules, and no part of this module is allowed to grow into one.
 *
 * <p><b>The plan, and why it is decided late.</b> Travel runs as at most two hops:
 * <ol>
 *   <li><b>Off-island</b> – run the island's own travel command and wait for the island to change.
 *       Coordinates are island-scoped, so nothing may be marked before this lands.</li>
 *   <li><b>On-island</b> – pick the nearest warp that actually saves walking and run it.</li>
 * </ol>
 * The second hop is chosen only once you are standing on the island, from your real position, rather
 * than planned up front from a guess about where hop one drops you. That is what makes the
 * "is a warp even worth it" test honest, and it is automatically right when you were already on the
 * island to begin with.
 *
 * <p><b>Failure is expected, and never leaves a mess.</b> A refused warp (not unlocked), a rate limit,
 * or simply nothing happening within the timeout all end the same way: the record is updated, the
 * player is told in plain words, and any waypoint this class placed is removed. A marker left behind
 * pointing at a place you never reached is worse than no marker.
 */
public final class MapNavigation {

    private static final MapNavigation INSTANCE = new MapNavigation();

    /** Within this many blocks of the target the trip is done and the marker clears itself. */
    private static final double ARRIVE_DIST = 4.0;

    /**
     * A warp is only worth taking if it leaves you at least this much closer than you already are.
     * Without a margin, a warp that saves four blocks would still fire - costing a loading screen and
     * a cooldown to save two seconds of walking.
     */
    private static final double MIN_WARP_SAVING = 40.0;

    /** How close to a warp's arrival point counts as "that warp landed". */
    private static final double WARP_ARRIVED_DIST = 24.0;

    /** How long to wait for a hop before giving up on it. Island travel loads a world, so it is generous. */
    private static final long ISLAND_TIMEOUT_MS = 15_000L;
    private static final long WARP_TIMEOUT_MS = 8_000L;

    /** An armed trip that has gone nowhere for this long has been abandoned; stop marking. */
    private static final long GUIDE_TIMEOUT_MS = 10 * 60_000L;

    /** What the navigator is doing. */
    private enum Phase {
        /** Nothing armed. */
        IDLE,
        /** An island travel command is out; waiting for the island to change. */
        TRAVELLING,
        /** A warp within the island is out; waiting to land near its arrival point. */
        WARPING,
        /** On the right island: marker published, route drawn, walking is up to the player. */
        GUIDING,
        /**
         * Another island, reached by a learned transfer jump pad: the pad is marked and routed to,
         * and the player walks onto it. Nothing is sent - stepping on a pad is the player's action.
         */
        PAD_HOP
    }

    private Phase phase = Phase.IDLE;

    private MapLocation target;
    private IslandMap targetMap;

    /** The warp hop currently in flight, or {@code null}. */
    private MapWarp pendingWarp;

    /** When the hop in flight was sent, and when the whole trip was armed. */
    private long sentAt;
    private long armedAt;

    /**
     * When {@link #pendingWarp} may be sent, or {@code 0} when it already has been.
     *
     * <p>A warp decided during another warp's cooldown is held here rather than fired late from a
     * timer thread: the tick is already running, and "send it on the first tick at or after this
     * moment" needs nothing more than a deadline. Cancelling the trip drops the deadline with it, so
     * a queued command can never outlive the plan that wanted it.
     */
    private long warpDueAt;

    /**
     * Whether the on-island warp hop has been decided already. One attempt, plus one alternative if
     * the first turns out to be locked - a navigator that kept trying warps would spam commands.
     */
    private boolean warpConsidered;
    private boolean warpRetried;

    /** Set from the chat thread, consumed on the next tick - see {@link #onChat}. */
    private volatile boolean sawLocked;
    private volatile boolean sawTooFast;
    private volatile boolean sawRefused;

    private MapNavigation() {
    }

    public static MapNavigation getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.MapSettings cfg() {
        return ConfigManager.getInstance().get().map;
    }

    // ------------------------------------------------------------------ public state

    /** Whether a trip is armed (the map screen shows this, and the settings status line). */
    public boolean active() {
        return phase != Phase.IDLE && target != null;
    }

    /** The place being travelled to, or {@code null}. */
    public MapLocation target() {
        return target;
    }

    /** One line describing what the navigator is doing, for the settings page and the map screen. */
    public String statusLine() {
        if (!active()) {
            return "§7Not navigating";
        }
        return switch (phase) {
            case TRAVELLING -> "§bTravelling to " + targetMap.displayName() + "…";
            case WARPING -> "§bWarping to " + (pendingWarp == null ? "?" : pendingWarp.displayLabel()) + "…";
            case GUIDING -> "§aGuiding to " + target.name;
            case PAD_HOP -> "§bTo the jump pad for " + targetMap.displayName() + "…";
            case IDLE -> "§7Not navigating";
        };
    }

    // ------------------------------------------------------------------ intake

    /**
     * Map click entry point: travel to {@code location} on {@code map}.
     *
     * <p>Cancels whatever was armed before - a second click is a new destination, not a queue.
     */
    public void travelTo(IslandMap map, MapLocation location) {
        if (map == null || location == null) {
            return;
        }
        cancel(false);

        target = location;
        targetMap = map;
        armedAt = System.currentTimeMillis();
        warpConsidered = false;
        warpRetried = false;
        clearChatFlags();

        boolean here = SkyBlockLocation.onIsland(map.island);

        // Another island with a learned transfer pad to it. Used when there is no travel command to
        // send for the player (auto-warp off, or an island with no travel command): a command is
        // instant, a pad is a walk, so the command still wins when the player allowed it.
        boolean commandHop = cfg().autoWarp && map.travel != null && !map.travel.isBlank();
        if (!here && !commandHop) {
            List<JumpPads.TransferPad> pads = JumpPads.getInstance()
                    .transfersTo(SkyBlockLocation.island(), map.island);
            if (!pads.isEmpty()) {
                startPadHop(pads.getFirst());
                return;
            }
        }

        if (!cfg().autoWarp) {
            // Auto-warp off: mark and route, but say which warp would have been used so the player
            // can run it themselves. The whole point of the toggle is not sending commands.
            phase = Phase.GUIDING;
            MapWarp suggestion = here ? bestWarpFrom(playerPos(), location) : null;
            SBSChat.send(Component.literal(" Marked " + location.name)
                    .withColor(SBSChat.PREFIX_COLOR)
                    .append(Component.literal(here
                                    ? (suggestion == null ? "" : "  – nearest warp: " + suggestion.command)
                                    : "  – on " + map.displayName()
                                      + (map.travel == null || map.travel.isBlank()
                                              ? "" : ", travel with " + map.travel))
                            .withColor(SBSChat.WHITE)));
            tick(Minecraft.getInstance());
            return;
        }

        if (!here) {
            if (map.travel == null || map.travel.isBlank()) {
                // Nothing to run: an island reached by portal only. Arm the marker anyway - it will
                // appear the moment the player gets there under their own steam.
                phase = Phase.GUIDING;
                SBSChat.send(Component.literal(" " + location.name + " is on " + map.displayName()
                        + " – travel there and the marker appears.").withColor(SBSChat.WHITE));
                return;
            }
            startIslandHop();
            return;
        }

        // Already on the island: go straight to the warp decision.
        phase = Phase.GUIDING;
        considerWarp();
        tick(Minecraft.getInstance());
    }

    /** Cancels the trip and removes anything it placed. */
    public void cancel(boolean announce) {
        boolean had = active();
        phase = Phase.IDLE;
        target = null;
        targetMap = null;
        pendingWarp = null;
        warpDueAt = 0;
        warpConsidered = false;
        warpRetried = false;
        clearChatFlags();
        clearWaypoint();
        WarpAvailability.clearCooldown();
        if (had && announce) {
            SBSChat.send(Component.literal(" Navigation cancelled.").withColor(SBSChat.WHITE));
        }
    }

    // ------------------------------------------------------------------ chat

    /**
     * Watches for Hypixel's answer to a warp.
     *
     * <p>Only flags are set here; the work happens in {@link #tick}. Chat is delivered while packets
     * are handled, and doing the state transition there would run it in the middle of whatever else
     * that packet triggers - the same reason every other tracker in this mod defers to the tick.
     *
     * <p><b>These patterns need live tuning.</b> Hypixel's exact wording is not documented anywhere
     * and changes; every warp outcome is logged under {@code [SBS][Map]} so a line that this build
     * fails to recognise can be read out of the log and added here.
     */
    public void onChat(String text) {
        if (phase != Phase.TRAVELLING && phase != Phase.WARPING) {
            return;
        }
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (lower.isEmpty()) {
            return;
        }
        if (lower.contains("haven't unlocked") || lower.contains("have not unlocked")
                || lower.contains("unlock this fast travel")) {
            sawLocked = true;
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Map] warp refused (locked): {}", text);
        } else if (lower.contains("too fast") || lower.contains("slow down")
                || lower.contains("wait a few seconds")) {
            sawTooFast = true;
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Map] warp rate-limited: {}", text);
        } else if (lower.contains("cannot warp") || lower.contains("can't warp")
                || lower.contains("you are in combat") || lower.contains("while in combat")) {
            sawRefused = true;
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Map] warp refused: {}", text);
        }
    }

    private void clearChatFlags() {
        sawLocked = false;
        sawTooFast = false;
        sawRefused = false;
    }

    // ------------------------------------------------------------------ tick

    /** Called every client tick from {@code GuiTrackingMixin}. */
    public void tick(Minecraft minecraft) {
        if (phase == Phase.IDLE || target == null || targetMap == null) {
            return;
        }
        if (!cfg().enabled) {
            cancel(false);
            return;
        }
        Player player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return;
        }
        if (consumeFailure()) {
            return;
        }
        switch (phase) {
            case TRAVELLING -> tickTravelling();
            case WARPING -> tickWarping(player);
            case GUIDING -> tickGuiding(player);
            case PAD_HOP -> tickPadHop();
            default -> {
            }
        }
    }

    /**
     * Handles whatever chat reported since the last tick. Returns {@code true} when the trip has been
     * dealt with (aborted or retried) and this tick should do nothing further.
     */
    private boolean consumeFailure() {
        if (sawLocked) {
            // Attribute the refusal to the hop actually in flight. Guessing when there is none would
            // mark the wrong command locked, and a wrongly locked warp stays wrong until it is reset
            // by hand - Hypixel never announces an unlock.
            String command = phase == Phase.TRAVELLING ? targetMap.travel
                    : pendingWarp != null ? pendingWarp.command : null;
            clearChatFlags();
            if (command == null || command.isBlank()) {
                return false;
            }
            WarpAvailability.markLocked(command);
            // A locked island hop ends the trip; a locked warp within the island can fall back to the
            // next-nearest one, and failing that to simply walking from where we are.
            if (phase == Phase.TRAVELLING) {
                abort("You have not unlocked " + command + ".");
                return true;
            }
            pendingWarp = null;
            phase = Phase.GUIDING;
            if (!warpRetried) {
                warpRetried = true;
                warpConsidered = false;   // let considerWarp pick the next candidate
                considerWarp();
            }
            return true;
        }
        if (sawTooFast) {
            clearChatFlags();
            WarpAvailability.noteTooFast();
            // Push the deadline out rather than aborting: the command was rejected, not the plan.
            sentAt = System.currentTimeMillis() + WarpAvailability.cooldownRemaining();
            return true;
        }
        if (sawRefused) {
            clearChatFlags();
            abort("Hypixel refused the warp (in combat, or not allowed here).");
            return true;
        }
        return false;
    }

    /** Waiting for an island travel command to land. */
    private void tickTravelling() {
        if (SkyBlockLocation.onIsland(targetMap.island)) {
            WarpAvailability.markUnlocked(targetMap.travel);
            phase = Phase.GUIDING;
            warpConsidered = false;
            considerWarp();
            return;
        }
        if (System.currentTimeMillis() - sentAt > ISLAND_TIMEOUT_MS) {
            abort("Travel to " + targetMap.displayName() + " did not go through.");
        }
    }

    /** Waiting for an on-island warp to land - or for its cooldown to run out so it can be sent. */
    private void tickWarping(Player player) {
        if (pendingWarp == null) {
            phase = Phase.GUIDING;
            return;
        }
        if (warpDueAt > 0) {
            if (System.currentTimeMillis() < warpDueAt) {
                return;   // still inside the previous warp's cooldown
            }
            warpDueAt = 0;
            sentAt = System.currentTimeMillis();
            send(pendingWarp.command);
            return;
        }
        if (SkyBlockLocation.onIsland(targetMap.island)
                && player.position().distanceTo(pendingWarp.position()) <= WARP_ARRIVED_DIST) {
            WarpAvailability.markUnlocked(pendingWarp.command);
            pendingWarp = null;
            phase = Phase.GUIDING;
            return;
        }
        if (System.currentTimeMillis() - sentAt > WARP_TIMEOUT_MS) {
            // No refusal in chat and no arrival: the warp may simply have a different arrival point
            // than the data says. Guide from wherever we actually are rather than calling it a
            // failure - the marker and route are correct either way.
            SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Map] '{}' did not land near its catalogued arrival point ({}, {}, {}) - "
                            + "guiding from the current position instead",
                    pendingWarp.command, pendingWarp.x, pendingWarp.y, pendingWarp.z);
            pendingWarp = null;
            phase = Phase.GUIDING;
        }
    }

    /** On the right island: keep the marker published until the player arrives (or gives up). */
    private void tickGuiding(Player player) {
        if (System.currentTimeMillis() - armedAt > GUIDE_TIMEOUT_MS) {
            cancel(false);
            return;
        }
        if (!SkyBlockLocation.onIsland(targetMap.island)) {
            // Left the island - the coordinates mean something else here. Keep the trip armed (it
            // re-engages on return, like the NPC locator) but nothing may be drawn meanwhile.
            clearWaypoint();
            return;
        }
        if (player.position().distanceTo(new Vec3(target.x, target.y, target.z)) <= ARRIVE_DIST) {
            SBSChat.send(Component.literal(" Arrived at " + target.name + ".")
                    .withColor(SBSChat.PREFIX_COLOR));
            cancel(false);
            return;
        }
        ensureWaypoint();
    }

    // ------------------------------------------------------------------ hops

    /** Marks the transfer pad and routes to it; {@link #tickPadHop} notices the arrival. */
    private void startPadHop(JumpPads.TransferPad pad) {
        phase = Phase.PAD_HOP;
        padFromIsland = SkyBlockLocation.island();
        clearWaypoint();
        Waypoint waypoint = new Waypoint("Jump pad → " + targetMap.displayName(), pad.stand(),
                WaypointStore.currentDimension(), Waypoint.SOURCE_MAP);
        waypoint.throughWalls = cfg().throughWalls;
        waypoint.showDistance = cfg().showDistance;
        WaypointStore.publish(waypoint);
        SBSChat.send(Component.literal(" " + target.name + " is on " + targetMap.displayName()
                + " – step on the marked jump pad" + (pad.certainty() == JumpPad.Certainty.CONFIRMED
                        ? "." : " (seen once, not yet confirmed).")).withColor(SBSChat.PREFIX_COLOR));
    }

    /** Waiting for the pad to carry the player across; then the ordinary guiding takes over. */
    private void tickPadHop() {
        if (SkyBlockLocation.onIsland(targetMap.island)) {
            clearWaypoint();
            phase = Phase.GUIDING;
            warpConsidered = false;
            considerWarp();
            return;
        }
        if (System.currentTimeMillis() - armedAt > GUIDE_TIMEOUT_MS) {
            cancel(false);
            return;
        }
        if (padFromIsland != null && !SkyBlockLocation.onIsland(padFromIsland)) {
            // Went somewhere else entirely: the pad marker means nothing here. The trip stays armed
            // and re-engages if the player comes back, like the guiding phase does.
            clearWaypoint();
        }
    }

    /** The island the pad hop started on, so leaving it for a third island clears the marker. */
    private String padFromIsland;

    private void startIslandHop() {
        phase = Phase.TRAVELLING;
        sentAt = System.currentTimeMillis();
        send(targetMap.travel);
        SBSChat.send(Component.literal(" Travelling to " + targetMap.displayName()
                        + " for " + target.name + "…").withColor(SBSChat.PREFIX_COLOR));
    }

    /**
     * Decides whether a warp on this island is worth taking, and takes it.
     *
     * <p>The rule the feature is built around: a warp is only used when it leaves you closer than you
     * already are, by a real margin. Standing next to the Bazaar, "go to the Bazaar" should draw a
     * ten-block route, not warp you to the Hub spawn first.
     */
    private void considerWarp() {
        if (warpConsidered || !cfg().autoWarp) {
            return;
        }
        warpConsidered = true;

        Vec3 from = playerPos();
        if (from == null) {
            return;
        }
        MapWarp best = bestWarpFrom(from, target);
        if (best == null) {
            return;   // nothing unlocked helps: walk from here
        }
        pendingWarp = best;
        phase = Phase.WARPING;

        long wait = WarpAvailability.cooldownRemaining();
        if (wait > 0) {
            // The island hop just went out. Waiting is the whole point of tracking the cooldown -
            // firing now would earn a "too fast" and cost more time than the pause.
            warpDueAt = System.currentTimeMillis() + wait;
            sentAt = warpDueAt;   // the hop timeout only starts once the command is actually out
            return;
        }
        warpDueAt = 0;
        sentAt = System.currentTimeMillis();
        send(best.command);
    }

    /** The nearest warp to {@code location} that is unlocked and saves a worthwhile walk. */
    private MapWarp bestWarpFrom(Vec3 from, MapLocation location) {
        if (from == null) {
            return null;
        }
        double currentDistance = Math.sqrt(
                (from.x - location.x) * (from.x - location.x)
                        + (from.z - location.z) * (from.z - location.z));
        List<MapWarp> candidates = targetMap.warpsNearest(location);
        for (MapWarp warp : candidates) {
            if (!WarpAvailability.usable(warp.command)) {
                continue;
            }
            double afterWarp = warp.horizontalDistanceTo(location.x, location.z);
            if (currentDistance - afterWarp >= MIN_WARP_SAVING) {
                return warp;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ waypoint

    /**
     * Publishes (or refreshes) the marker for the target.
     *
     * <p>Only ever one map waypoint exists: a second click replaces the first rather than littering
     * the world with everywhere you have ever considered going.
     */
    private void ensureWaypoint() {
        for (Waypoint waypoint : WaypointStore.all()) {
            if (waypoint.isMap() && waypoint.x == target.x && waypoint.z == target.z) {
                return;   // already published
            }
        }
        clearWaypoint();

        SBSConfig.MapSettings cfg = cfg();
        Waypoint waypoint = new Waypoint(target.name, target.pos(),
                WaypointStore.currentDimension(), Waypoint.SOURCE_MAP);
        waypoint.throughWalls = cfg.throughWalls;
        waypoint.showDistance = cfg.showDistance;
        WaypointStore.publish(waypoint);
    }

    private void clearWaypoint() {
        WaypointStore.removeSource(Waypoint.SOURCE_MAP);
    }

    // ------------------------------------------------------------------ helpers

    private void abort(String reason) {
        SBSChat.send(Component.literal(" " + reason).withColor(0xFF6B6B));
        cancel(false);
    }

    private static Vec3 playerPos() {
        Player player = Minecraft.getInstance().player;
        return player == null ? null : player.position();
    }

    /**
     * Runs a command the way the warp menu does - through {@link SBSCommands#run}, so a command
     * shortcut the player has configured applies here too.
     */
    private void send(String command) {
        WarpAvailability.noteWarpSent();
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Map] sending '{}' toward {}", command, target.name);
        Minecraft.getInstance().execute(() -> SBSCommands.run(command));
    }

}
