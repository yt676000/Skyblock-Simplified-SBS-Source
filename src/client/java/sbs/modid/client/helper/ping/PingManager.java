/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.ping;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.pathfinding.Waypoint;
import sbs.modid.client.core.pathfinding.WaypointStore;
import sbs.modid.client.core.render.OverlayColor;

import java.util.ArrayList;
import java.util.List;

/**
 * The live ping set: places one, removes one, expires them, and keeps the entity-anchored ones on
 * their entity.
 *
 * <p><b>Nothing here draws.</b> A ping is a transient {@link Waypoint} under
 * {@link Waypoint#SOURCE_PING}, and the shared {@code PathRenderer} gives it the box, the beam, the
 * label and the live distance every other marker in this mod gets. That is why this class is short
 * and has no render package beside it.
 *
 * <p><b>Nothing here is persisted, structurally.</b> {@link WaypointStore#setTransient} is the only
 * way in, and it never touches {@code config.json}. There is no code path from a ping to a save.
 *
 * <p><b>Why the set is published but the markers are mutated.</b> {@code setTransient} invalidates
 * the pathfinder, which is correct when a marker set appears or disappears and ruinous at tick rate:
 * a ping stuck to a walking mob would restart the current A* search twenty times a second. So the
 * set is published only when its <i>membership</i> changes - a ping placed, removed or expired - and
 * the per-tick work (following an entity, fading out) is written straight onto the {@code Waypoint}
 * objects the store is already holding. The renderer reads position, colour and opacity every frame,
 * so an in-place write is on screen on the next one.
 *
 * <p><b>No area gate anywhere in this file.</b> A ping is a thing the player points at; it is as
 * valid in a dungeon as on the Hub, so there is no island, zone or instance check to get wrong.
 */
public final class PingManager {

    private static final PingManager INSTANCE = new PingManager();

    /**
     * Minimum time between two ping actions. Covers removal as well as placement: it is one key
     * doing two things, and rate-limiting half of it would let a held button strobe the set.
     */
    private static final long COOLDOWN_MS = 400L;

    /** A ping spends its last second fading out, so it leaves rather than blinking off. */
    private static final long FADE_MS = 1_000L;

    /**
     * How far off the crosshair a ping may sit and still be the one the press removes.
     *
     * <p>An angle rather than a hitbox because a ping has no body: there is nothing to clip a ray
     * against, and the block it sits on is often behind the thing the player is looking at. A
     * constant rather than a setting - it is a feel, not a preference, and a slider for it would be
     * one more row nobody reads.
     */
    private static final double REMOVE_ANGLE_DEG = 8.0;

    /** What every ping's label says, before the renderer appends the distance. */
    private static final String LABEL = "Ping";

    /** Newest last, so the oldest is always index 0 when the cap is hit. */
    private final List<ActivePing> pings = new ArrayList<>();

    /** Wall-clock time of the last placement or removal, for the cooldown. */
    private long lastActionAt;

    /** The dimension the current set belongs to; a change to it invalidates every ping. */
    private String dimension = "";

    private PingManager() {
    }

    public static PingManager getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.PingSettings cfg() {
        return ConfigManager.getInstance().get().ping;
    }

    /** One ping: the marker the renderer draws, what it follows, and when it dies. */
    private static final class ActivePing {

        private final Waypoint waypoint;

        /**
         * The entity this marker follows, or {@code null} for a fixed spot.
         *
         * <p>Cleared rather than acted on when the entity dies or unloads: the marker keeps the last
         * position it had. A ping is a place the player pointed at, and "where it was when it died"
         * is still that place - dropping the marker instead would delete information mid-callout.
         */
        private Entity entity;

        private final long expiresAt;

        private ActivePing(Waypoint waypoint, Entity entity, long expiresAt) {
            this.waypoint = waypoint;
            this.entity = entity;
            this.expiresAt = expiresAt;
        }

        /** Moves the marker onto its entity, if it still has one. */
        private void follow() {
            if (entity == null) {
                return;
            }
            if (entity.isRemoved() || !entity.isAlive()) {
                entity = null;
                return;
            }
            BlockPos pos = entity.blockPosition();
            waypoint.x = pos.getX();
            waypoint.y = pos.getY();
            waypoint.z = pos.getZ();
        }

        /** Ramps {@link Waypoint#opacity} down over the last {@link #FADE_MS} of the lifetime. */
        private void fade(long now) {
            long remaining = expiresAt - now;
            waypoint.opacity = remaining >= FADE_MS
                    ? 100
                    : (int) Math.max(0, Math.round(100.0 * remaining / FADE_MS));
        }

        /** The centre of the block the marker sits on - what the removal angle is measured to. */
        private Vec3 centre() {
            return new Vec3(waypoint.x + 0.5, waypoint.y + 0.5, waypoint.z + 0.5);
        }
    }

    // ------------------------------------------------------------------
    // The keybind
    // ------------------------------------------------------------------

    /**
     * The bind was pressed: remove the ping being looked at, or place a new one where the ray lands.
     *
     * <p>Removal is tested first on purpose. Placing and then removing with the same key is the
     * whole interaction, and a press that lands on an existing marker is far more likely to mean
     * "take that away" than "put a second one just behind it".
     */
    public void onPressed() {
        SBSConfig.PingSettings cfg = cfg();
        if (!cfg.enabled) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastActionAt < COOLDOWN_MS) {
            return;
        }
        lastActionAt = now;

        ActivePing aimed = aimedAt(player);
        if (aimed != null) {
            pings.remove(aimed);
            publish();
            return;
        }
        place(cfg, level, player, now);
    }

    /** Casts the ray and puts a marker where it lands. */
    private void place(SBSConfig.PingSettings cfg, ClientLevel level, LocalPlayer player, long now) {
        String currentDimension = WaypointStore.currentDimension();
        if (currentDimension.isEmpty()) {
            return;   // between worlds; a marker with no dimension is drawn nowhere and routed over
        }
        PingRay.Aim aim = PingRay.resolve(level, player, cfg.maxDistance, cfg.followEntities);

        Waypoint waypoint = new Waypoint(LABEL, aim.block(), currentDimension, Waypoint.SOURCE_PING);
        waypoint.colorHex = OverlayColor.toHex(cfg.rgb());
        waypoint.showDistance = cfg.showDistance;
        // Through walls, like every marker whose job is to be found: a ping the wall hides is a ping
        // that failed at the one thing it was placed for. This is also the renderer's default, so it
        // costs no occlusion ray per frame.
        waypoint.throughWalls = true;
        // Never a pathfinder destination - it moves and it expires. PathRouting skips pings in its
        // nearest-waypoint pick for the same reason; this says so on the marker as well.
        waypoint.routable = false;

        dimension = currentDimension;
        pings.add(new ActivePing(waypoint, aim.entity(), now + lifetimeMs(cfg)));
        // The cap is a while-loop rather than a single remove: lowering the setting while pings are
        // out has to bring the set down to the new number, not one below the old one.
        while (pings.size() > Math.max(1, cfg.maxPings)) {
            pings.remove(0);
        }
        publish();
        playPlacementSound(player, cfg);
    }

    /** The ping closest to the crosshair within {@link #REMOVE_ANGLE_DEG}, or {@code null}. */
    private ActivePing aimedAt(LocalPlayer player) {
        if (pings.isEmpty()) {
            return null;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0f);
        ActivePing best = null;
        double bestAngle = REMOVE_ANGLE_DEG;

        for (ActivePing ping : pings) {
            Vec3 toPing = ping.centre().subtract(eye);
            double length = toPing.length();
            if (length < 1.0e-4) {
                return ping;   // standing inside it: there is no direction, and it is plainly the one
            }
            double angle = Math.toDegrees(Math.acos(
                    Mth.clamp(look.dot(toPing.scale(1.0 / length)), -1.0, 1.0)));
            if (angle < bestAngle) {
                bestAngle = angle;
                best = ping;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // Lifetime
    // ------------------------------------------------------------------

    /** Client tick. Returns immediately while nothing is out, which is almost always. */
    public void onClientTick() {
        if (pings.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (!cfg().enabled || minecraft.player == null || minecraft.level == null) {
            clear();
            return;
        }
        // A dimension change without leaving the world would silently stop every ping being drawn -
        // a waypoint is filtered by the dimension it was built for - so it clears them instead.
        String currentDimension = WaypointStore.currentDimension();
        if (!currentDimension.isEmpty() && !currentDimension.equals(dimension)) {
            clear();
            return;
        }
        long now = System.currentTimeMillis();
        boolean expired = pings.removeIf(ping -> now >= ping.expiresAt);
        for (ActivePing ping : pings) {
            ping.follow();
            ping.fade(now);
        }
        if (expired) {
            publish();
        }
    }

    /** Called on a world change or server hop: a ping belongs to the instance it was placed in. */
    public void onWorldChange() {
        clear();
    }

    /** Drops every ping. */
    public void clear() {
        if (pings.isEmpty()) {
            return;
        }
        pings.clear();
        dimension = "";
        WaypointStore.clearTransient(Waypoint.SOURCE_PING);
    }

    /**
     * Re-applies the settings that live on the marker itself to the pings already out.
     *
     * <p>Called from the settings rows so a colour change is visible on screen at once rather than
     * on the next ping. Mutation in place, deliberately - see the class doc for why this must not
     * go through {@code setTransient}.
     */
    public void refresh() {
        SBSConfig.PingSettings cfg = cfg();
        if (!cfg.enabled) {
            clear();
            return;
        }
        String hex = OverlayColor.toHex(cfg.rgb());
        for (ActivePing ping : pings) {
            ping.waypoint.colorHex = hex;
            ping.waypoint.showDistance = cfg.showDistance;
        }
    }

    /** How many pings are up right now - what the settings page reports. */
    public int count() {
        return pings.size();
    }

    /**
     * Why nothing is on screen, in one line, for the settings page. A feature that draws nothing
     * without saying why is the one that gets reported as broken while working exactly as told.
     */
    public String status() {
        SBSConfig.PingSettings cfg = cfg();
        if (!cfg.enabled) {
            return "switched off - the keybind does nothing";
        }
        if (cfg.key == 0) {
            return "no key bound - nothing can place a ping";
        }
        return pings.isEmpty()
                ? "ready - no ping out right now"
                : pings.size() + " ping(s) out, up to " + Math.max(1, cfg.maxPings);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /** Hands the whole set to the store. Only ever called when its membership changed. */
    private void publish() {
        List<Waypoint> out = new ArrayList<>(pings.size());
        for (ActivePing ping : pings) {
            out.add(ping.waypoint);
        }
        WaypointStore.setTransient(Waypoint.SOURCE_PING, out);
    }

    private static long lifetimeMs(SBSConfig.PingSettings cfg) {
        return Math.max(1, cfg.lifetimeSeconds) * 1_000L;
    }

    /**
     * A short blip on placement, through the game's own sound engine.
     *
     * <p>Not {@code core/audio/SbsAudio}: that path exists so an <i>alert</i> is still heard by a
     * player who has muted Minecraft, and this is the opposite kind of sound - feedback for a button
     * the player just pressed, which should follow the game's volume like every other click does.
     */
    private static void playPlacementSound(LocalPlayer player, SBSConfig.PingSettings cfg) {
        if (!cfg.sound || cfg.soundVolume <= 0) {
            return;
        }
        player.playSound(SoundEvents.NOTE_BLOCK_PLING.value(),
                Math.min(100, cfg.soundVolume) / 100.0f, 1.9f);
    }
}
