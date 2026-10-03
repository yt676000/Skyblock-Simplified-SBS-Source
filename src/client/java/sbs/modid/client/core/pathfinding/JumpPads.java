/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.pathfinding;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.core.location.SkyBlockLocation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Jump pads, learned from real launches: per island, where you stand to be launched and where you
 * come down.
 *
 * <p><b>Learned, never copied.</b> The file starts empty. A launch is recorded when the player stands
 * on a slime block, leaves it moving upward fast, and comes down far away a while later. One launch
 * is {@link JumpPad.Certainty#LEARNED}; a second one landing within {@link #SAME_LANDING} blocks makes
 * it {@link JumpPad.Certainty#CONFIRMED}. {@code /sbs pad here} + {@code /sbs pad confirm} record one
 * by hand, CONFIRMED at once, for islands mapped on purpose.
 *
 * <p><b>Not a pad: jumps and teleports.</b> A jump off a slime block is refused by speed - the launch
 * must beat the player's own jump velocity (0.42 plus 0.1 per Jump Boost level) by
 * {@link #JUMP_MARGIN}. A teleport - Instant or Ether Transmission, Wither Impact, any ability, or a
 * server-side teleport - is refused by shape: a position change of more than {@link #MAX_TICK_STEP}
 * blocks in one tick, when leaving the pad or in flight, is not a flight. No item list is needed, so
 * a new teleport item is covered the day it ships.
 *
 * <p><b>Transfer pads.</b> Most pads send you to another server. A launch during which the world
 * changes is kept pending until the new island is known, then stored as a {@link TransferPad} (from
 * island, pad, to island) - a hop for map navigation, never an A* edge, since the two ends are not in
 * the same world.
 *
 * <p><b>Riding or flying - both are handled, neither is assumed.</b> The flight may be the player
 * launched on their own, or the player sitting on a vehicle (an invisible stand) the server moves
 * along the arc - it has not been observed which. On foot: the launch is the player's upward speed,
 * the teleport filter is the player's per-tick step, the landing is touching ground. Riding: the
 * launch is <b>mounting</b> something on or beside a pad, the per-tick distance is <b>not</b> checked
 * while riding (the server may move the vehicle by teleport packets, which would trip it on every
 * legitimate flight), the landing is the dismount (or the ground right after it), and a dismount that
 * puts the player more than {@link #MAX_TICK_STEP} blocks from where the vehicle was is a teleport
 * away from the ride. Every tick of a possible launch logs the player's and the vehicle's type, id,
 * uuid, position and motion under {@code [SBS][Pads] tick}, so the first real flight settles it.
 *
 * <p><b>What a pad is, is not verified.</b> The detection rule (slime block underfoot, upward speed
 * over {@link #LAUNCH_SPEED}, landing at least {@link #MIN_FLIGHT_BLOCKS} away after at least
 * {@link #MIN_FLIGHT_MS}) is an assumption. Every candidate is logged under {@code [SBS][Pads]} with
 * its launch velocity, positions and flight time, so the first real launches settle the numbers.
 * A flight during which the world changes is a server transfer, logged and never stored: that
 * belongs to map-level routing, not to A*.
 *
 * <p>Global, not per profile: a pad is a fact about an island, the same for every player.
 */
public final class JumpPads {

    private static final JumpPads INSTANCE = new JumpPads();

    private static final String FILE = "jump_pads.json";
    private static final int SCHEMA_VERSION = 1;

    /** Upward speed (blocks/tick) that counts as a launch rather than a jump or a slime bounce. */
    static final double LAUNCH_SPEED = 0.9;
    /** A launch must leave the slime within this long of standing on it. */
    private static final long LEAVE_WINDOW_MS = 600L;
    static final double MIN_FLIGHT_BLOCKS = 8.0;
    static final long MIN_FLIGHT_MS = 700L;
    private static final long MAX_FLIGHT_MS = 15_000L;
    /** Two launches landing this close are the same pad behaving the same way. */
    static final double SAME_LANDING = 4.0;
    /** A launch must beat the player's own jump velocity by this much. */
    static final double JUMP_MARGIN = 0.35;
    /** More movement than this in one tick is a teleport, not a flight (a launch covers ~1-2). */
    static final double MAX_TICK_STEP = 3.5;
    /** How long a transfer may take to name the new island (the tab list can take ~60 s). */
    private static final long TRANSFER_WAIT_MS = 90_000L;

    /**
     * A pad that sends you to another server: stand on it on {@code fromIsland} (the map key) and you
     * arrive on {@code toIsland}.
     */
    public record TransferPad(BlockPos stand, String toIsland, int launches, JumpPad.Certainty certainty) {
    }

    private final Map<String, List<JumpPad>> byIsland = new LinkedHashMap<>();
    private final Map<String, List<TransferPad>> transfersByIsland = new LinkedHashMap<>();
    private boolean loaded;
    private volatile int generation;

    // Learner state.
    private BlockPos onPad;
    private long onPadAt;
    private boolean inFlight;
    private BlockPos flightFrom;
    private long launchAt;
    private Vec3 launchVelocity;
    private Level flightLevel;
    private String flightIsland;
    private Vec3 lastPos;
    /** The flight is on a vehicle (see the class doc); its end is the dismount. */
    private boolean ridden;
    private Vec3 lastVehiclePos;
    private boolean dismounted;
    private long dismountAt;
    /** Where the player last stood, for "mounted next to a pad". */
    private BlockPos lastGround;
    /** How long after a dismount the player may take to touch ground before it counts anyway. */
    private static final long DISMOUNT_SETTLE_MS = 3_000L;

    // A launch that changed the world, waiting for the new island's name.
    private BlockPos transferFrom;
    private String transferFromIsland;
    private long transferAt;

    // Manual recording.
    private BlockPos manualStand;
    private String manualIsland;
    private long manualAt;

    private JumpPads() {
    }

    public static JumpPads getInstance() {
        return INSTANCE;
    }

    /** Bumped whenever a pad is added or changed, so a route can be re-planned with it. */
    public int generation() {
        return generation;
    }

    /** Whether the player is mid-flight from a pad - the route must not be re-planned from the air. */
    public boolean inFlight() {
        return inFlight;
    }

    /** The transfer pads on {@code fromIsland} that lead to {@code toIsland}, CONFIRMED first. */
    public synchronized List<TransferPad> transfersTo(String fromIsland, String toIsland) {
        ensureLoaded();
        List<TransferPad> all = transfersByIsland.get(fromIsland == null ? "" : fromIsland);
        if (all == null || toIsland == null) {
            return List.of();
        }
        List<TransferPad> out = new ArrayList<>();
        for (TransferPad pad : all) {
            if (pad.toIsland().equalsIgnoreCase(toIsland)) {
                out.add(pad);
            }
        }
        out.sort((a, b) -> b.certainty().compareTo(a.certainty()));
        return out;
    }

    /** The pads known on {@code island}. */
    public synchronized List<JumpPad> forIsland(String island) {
        ensureLoaded();
        List<JumpPad> pads = byIsland.get(island == null ? "" : island);
        return pads == null ? List.of() : List.copyOf(pads);
    }

    // ------------------------------------------------------------------ learning

    /** Client tick. */
    public void tick(Minecraft minecraft) {
        Player player = minecraft.player;
        Level level = minecraft.level;
        if (player == null || level == null) {
            reset();
            return;
        }
        long now = System.currentTimeMillis();
        if (transferFrom != null) {
            tickTransfer(now);
        }
        Vec3 pos = player.position();
        double tickStep = lastPos == null ? 0 : pos.distanceTo(lastPos);
        lastPos = pos;
        net.minecraft.world.entity.Entity vehicle = player.getVehicle();
        if (inFlight || (onPad != null && now - onPadAt < LEAVE_WINDOW_MS) || (vehicle != null && nearPad(level))) {
            probeTick(player, vehicle, now);
        }
        if (inFlight) {
            if (level != flightLevel) {
                // Another server: remember the pad and wait for the new island to have a name.
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Pads] world changed mid-flight from {} on '{}' - "
                        + "a transfer pad; waiting for the new island", flightFrom, flightIsland);
                transferFrom = flightFrom;
                transferFromIsland = flightIsland;
                transferAt = now;
                reset();
                return;
            }
            long flight = now - launchAt;
            if (flight > MAX_FLIGHT_MS) {
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Pads] flight from {} never landed within {} ms - dropped",
                        flightFrom, MAX_FLIGHT_MS);
                reset();
                return;
            }
            if (ridden) {
                tickRide(player, vehicle, pos, now, flight);
                return;
            }
            if (isTeleportStep(false, false, tickStep, 0)) {
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Pads] {} blocks in one tick during the flight from "
                        + "{} - a teleport (ability or server), not a pad flight; dropped",
                        round(tickStep), flightFrom);
                reset();
                return;
            }
            if (player.onGround() && flight > 250) {
                land(player.blockPosition(), flight);
            }
            return;
        }
        // Mounting something on or beside a pad is the launch when the flight is a ride.
        if (vehicle != null) {
            BlockPos pad = onPad != null && now - onPadAt < LEAVE_WINDOW_MS ? onPad : nearPad(level) ? lastGround : null;
            if (pad != null && player.getControlledVehicle() != null) {
                // A mount the player steers (a horse standing on slime) is travel, not a pad.
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Pads] mounted {} at {}, but the player steers it - "
                        + "not a pad", vehicle.getType().toShortString(), pad);
                onPad = null;
                lastGround = null;
                return;
            }
            if (pad != null) {
                inFlight = true;
                ridden = true;
                flightFrom = pad;
                launchAt = now;
                launchVelocity = vehicle.getDeltaMovement();
                lastVehiclePos = vehicle.position();
                flightLevel = level;
                flightIsland = SkyBlockLocation.island();
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Pads] mounted {} (id {}, uuid {}) at pad {} on '{}' - "
                                + "a ridden launch", vehicle.getType().toShortString(), vehicle.getId(),
                        vehicle.getUUID(), pad, flightIsland);
                onPad = null;
            }
            return;
        }
        if (player.onGround()) {
            BlockPos feet = player.blockPosition();
            lastGround = feet;
            onPad = level.getBlockState(feet.below()).is(Blocks.SLIME_BLOCK) ? feet : null;
            if (onPad != null) {
                onPadAt = now;
            }
            return;
        }
        Vec3 velocity = player.getDeltaMovement();
        if (onPad != null && now - onPadAt < LEAVE_WINDOW_MS && velocity.y > 0.5) {
            double needed = launchThreshold(player);
            if (isTeleportStep(false, false, tickStep, 0)) {
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Pads] left the slime at {} by {} blocks in one tick "
                        + "- a teleport, not a launch; ignored", onPad, round(tickStep));
            } else if (velocity.y >= needed) {
                inFlight = true;
                flightFrom = onPad;
                launchAt = now;
                launchVelocity = velocity;
                flightLevel = level;
                flightIsland = SkyBlockLocation.island();
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Pads] launch from {} on '{}' velocity=({}, {}, {})",
                        onPad, flightIsland, round(velocity.x), round(velocity.y), round(velocity.z));
            } else {
                // The candidate the threshold turned down - logged so the threshold can be checked.
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Pads] slime bounce or jump at {} below launch speed "
                        + "(vy={}, needed {}), ignored", onPad, round(velocity.y), round(needed));
            }
            onPad = null;
        }
    }

    /** A ridden flight: follow the vehicle; the dismount (then the ground) is the landing. */
    private void tickRide(Player player, net.minecraft.world.entity.Entity vehicle, Vec3 pos, long now,
                          long flight) {
        if (vehicle != null && !dismounted) {
            // No per-tick distance check while riding: the server may move the vehicle by teleport.
            lastVehiclePos = vehicle.position();
            return;
        }
        if (!dismounted) {
            dismounted = true;
            dismountAt = now;
            double gap = lastVehiclePos == null ? 0 : pos.distanceTo(lastVehiclePos);
            if (isTeleportStep(false, true, 0, gap)) {
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Pads] dismounted {} blocks away from the vehicle "
                        + "from {} - a teleport away from the ride, not a landing; dropped", round(gap), flightFrom);
                reset();
                return;
            }
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Pads] dismounted at {} after {} ms", player.blockPosition(), flight);
        }
        if (player.onGround() || now - dismountAt > DISMOUNT_SETTLE_MS) {
            land(player.blockPosition(), flight);
        }
    }

    /**
     * The teleport rule, on its own so it is unit-tested. While {@code riding} it never fires: the
     * vehicle may be moved by teleport packets on a legitimate flight. On the dismount tick it looks
     * at the gap between the player and where the vehicle was; on foot, at the player's own step.
     */
    static boolean isTeleportStep(boolean riding, boolean dismountTick, double playerStep, double dismountGap) {
        if (riding) {
            return false;
        }
        return dismountTick ? dismountGap > MAX_TICK_STEP : playerStep > MAX_TICK_STEP;
    }

    /** Whether the player last stood on or right beside a slime block (for a mount next to a pad). */
    private boolean nearPad(Level level) {
        if (lastGround == null) {
            return false;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (level.getBlockState(lastGround.offset(dx, -1, dz)).is(Blocks.SLIME_BLOCK)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The probe: one line per tick of a possible launch, player and vehicle side by side. */
    private void probeTick(Player player, net.minecraft.world.entity.Entity vehicle, long now) {
        Vec3 pp = player.position();
        Vec3 pm = player.getDeltaMovement();
        String v = "none";
        if (vehicle != null) {
            Vec3 vp = vehicle.position();
            Vec3 vm = vehicle.getDeltaMovement();
            v = vehicle.getType().toShortString() + " id=" + vehicle.getId() + " uuid=" + vehicle.getUUID()
                    + " pos=(" + round(vp.x) + ", " + round(vp.y) + ", " + round(vp.z) + ") motion=("
                    + round(vm.x) + ", " + round(vm.y) + ", " + round(vm.z) + ")";
        }
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Pads] tick t={} passenger={} vehicle=[{}] player pos=({}, {}, {}) "
                        + "motion=({}, {}, {}) onGround={}", inFlight ? now - launchAt : -1, player.isPassenger(), v,
                round(pp.x), round(pp.y), round(pp.z), round(pm.x), round(pm.y), round(pm.z), player.onGround());
    }

    /**
     * The upward speed a launch needs: above {@link #LAUNCH_SPEED}, and above the player's own jump -
     * 0.42 plus 0.1 per Jump Boost level - by {@link #JUMP_MARGIN}, so a boosted jump off slime is not
     * mistaken for a pad.
     */
    static double launchThreshold(double jumpBoostLevels) {
        return Math.max(LAUNCH_SPEED, 0.42 + 0.1 * jumpBoostLevels + JUMP_MARGIN);
    }

    private static double launchThreshold(Player player) {
        var boost = player.getEffect(net.minecraft.world.effect.MobEffects.JUMP_BOOST);
        return launchThreshold(boost == null ? 0 : boost.getAmplifier() + 1);
    }

    /** A transfer is stored once the new island has a name different from the one left. */
    private void tickTransfer(long now) {
        String island = SkyBlockLocation.island();
        if (island != null && !island.isEmpty() && !island.equalsIgnoreCase(transferFromIsland)) {
            recordTransfer(transferFromIsland, transferFrom, island);
            transferFrom = null;
        } else if (now - transferAt > TRANSFER_WAIT_MS) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Pads] transfer from {} on '{}' never named a new island "
                    + "- dropped", transferFrom, transferFromIsland);
            transferFrom = null;
        }
    }

    synchronized void recordTransfer(String fromIsland, BlockPos stand, String toIsland) {
        ensureLoaded();
        List<TransferPad> pads = transfersByIsland.computeIfAbsent(fromIsland == null ? "" : fromIsland,
                k -> new ArrayList<>());
        TransferPad updated = mergeTransfer(pads, stand, toIsland);
        generation++;
        save();
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Pads] transfer pad {} on '{}' -> '{}': {} x{}",
                updated.stand(), fromIsland, toIsland, updated.certainty(), updated.launches());
    }

    /** Same pad and same destination: one more consistent transfer; a new destination starts over. */
    static TransferPad mergeTransfer(List<TransferPad> pads, BlockPos stand, String toIsland) {
        for (int i = 0; i < pads.size(); i++) {
            TransferPad known = pads.get(i);
            if (known.stand().distManhattan(stand) > 2) {
                continue;
            }
            TransferPad next = known.toIsland().equalsIgnoreCase(toIsland)
                    ? new TransferPad(known.stand(), known.toIsland(), known.launches() + 1, JumpPad.Certainty.CONFIRMED)
                    : new TransferPad(stand, toIsland, 1, JumpPad.Certainty.LEARNED);
            pads.set(i, next);
            return next;
        }
        TransferPad fresh = new TransferPad(stand, toIsland, 1, JumpPad.Certainty.LEARNED);
        pads.add(fresh);
        return fresh;
    }

    private void land(BlockPos landing, long flightMs) {
        double dx = landing.getX() - flightFrom.getX();
        double dz = landing.getZ() - flightFrom.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Pads] landed at {} after {} ms, {} blocks from {} "
                        + "(launch vy={})", landing, flightMs, round(distance), flightFrom,
                launchVelocity == null ? "?" : round(launchVelocity.y));
        if (distance >= MIN_FLIGHT_BLOCKS && flightMs >= MIN_FLIGHT_MS) {
            record(flightIsland, flightFrom, landing, flightMs, false);
        }
        reset();
    }

    private void reset() {
        inFlight = false;
        lastPos = null;
        ridden = false;
        lastVehiclePos = null;
        dismounted = false;
        onPad = null;
        flightFrom = null;
        launchVelocity = null;
        flightLevel = null;
        flightIsland = null;
    }

    // ------------------------------------------------------------------ manual

    /** {@code /sbs pad here}: arm a manual record at the pad under the player. */
    public String armHere(Player player) {
        manualStand = player.blockPosition();
        manualIsland = SkyBlockLocation.island();
        manualAt = System.currentTimeMillis();
        return "Pad marked at " + manualStand.toShortString() + " on " + manualIsland
                + ". Take the pad, then run /sbs pad confirm where you land.";
    }

    /** {@code /sbs pad confirm}: store the armed pad with the player's position as its landing. */
    public String confirmHere(Player player) {
        if (manualStand == null) {
            return "No pad marked - stand on it and run /sbs pad here first.";
        }
        if (!java.util.Objects.equals(manualIsland, SkyBlockLocation.island())) {
            manualStand = null;
            return "You are on another island now - that is a transfer, not a pad. Nothing stored.";
        }
        BlockPos landing = player.blockPosition();
        long ms = System.currentTimeMillis() - manualAt;
        record(manualIsland, manualStand, landing, Math.min(ms, MAX_FLIGHT_MS), true);
        String done = "Pad stored: " + manualStand.toShortString() + " -> " + landing.toShortString()
                + " on " + manualIsland + " (CONFIRMED).";
        manualStand = null;
        return done;
    }

    /** {@code /sbs pad list}: the pads on the current island. */
    public String describeIsland() {
        String island = SkyBlockLocation.island();
        List<JumpPad> pads = forIsland(island);
        List<TransferPad> transfers;
        synchronized (this) {
            transfers = List.copyOf(transfersByIsland.getOrDefault(island == null ? "" : island, List.of()));
        }
        if (pads.isEmpty() && transfers.isEmpty()) {
            return "No jump pads known on " + island + ".";
        }
        StringBuilder out = new StringBuilder(pads.size() + " jump pad(s) on " + island + ":");
        for (JumpPad pad : pads) {
            out.append("\n ").append(pad.stand().toShortString()).append(" -> ")
                    .append(pad.landing().toShortString()).append(" ").append(pad.certainty())
                    .append(" x").append(pad.launches());
        }
        for (TransferPad pad : transfers) {
            out.append("\n ").append(pad.stand().toShortString()).append(" -> ").append(pad.toIsland())
                    .append(" (transfer) ").append(pad.certainty()).append(" x").append(pad.launches());
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ store

    /**
     * Adds one launch. Same standing block (within a block) and a landing within
     * {@link #SAME_LANDING}: one more consistent launch. A different landing replaces the entry and
     * starts it over as LEARNED - the pad was moved, or it does not land the same way twice.
     */
    synchronized void record(String island, BlockPos stand, BlockPos landing, long flightMs, boolean manual) {
        ensureLoaded();
        String key = island == null ? "" : island;
        List<JumpPad> pads = byIsland.computeIfAbsent(key, k -> new ArrayList<>());
        JumpPad updated = merge(pads, stand, landing, flightMs, manual);
        generation++;
        save();
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Pads] {} pad {} -> {} on '{}': {} x{}",
                manual ? "manual" : "learned", updated.stand(), updated.landing(), key,
                updated.certainty(), updated.launches());
    }

    /** The merge rule on its own, so it is unit-tested. Mutates {@code pads}; returns the entry. */
    static JumpPad merge(List<JumpPad> pads, BlockPos stand, BlockPos landing, long flightMs, boolean manual) {
        for (int i = 0; i < pads.size(); i++) {
            JumpPad known = pads.get(i);
            if (known.stand().distManhattan(stand) > 2) {
                continue;
            }
            JumpPad next;
            if (Math.sqrt(known.landing().distSqr(landing)) <= SAME_LANDING) {
                int launches = known.launches() + 1;
                next = new JumpPad(known.stand(), known.landing(), (known.flightMs() + flightMs) / 2, launches,
                        manual || launches >= 2 ? JumpPad.Certainty.CONFIRMED : known.certainty());
            } else {
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Pads] pad at {} landed at {} this time, {} before - "
                        + "starting it over", stand, landing, known.landing());
                next = new JumpPad(stand, landing, flightMs, 1,
                        manual ? JumpPad.Certainty.CONFIRMED : JumpPad.Certainty.LEARNED);
            }
            pads.set(i, next);
            return next;
        }
        JumpPad fresh = new JumpPad(stand, landing, flightMs, 1,
                manual ? JumpPad.Certainty.CONFIRMED : JumpPad.Certainty.LEARNED);
        pads.add(fresh);
        return fresh;
    }

    private static Path file() {
        return SBSFiles.root().resolve(FILE);
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path path = file();
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            JsonObject root = SBSFiles.GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), JsonObject.class);
            if (root == null || (!root.has("islands") && !root.has("transfers"))) {
                return;
            }
            if (root.has("schemaVersion") && root.get("schemaVersion").getAsInt() > SCHEMA_VERSION) {
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Pads] {} is from a newer build - not read", FILE);
                return;
            }
            JsonObject islandsJson = root.has("islands") ? root.getAsJsonObject("islands") : new JsonObject();
            for (var island : islandsJson.entrySet()) {
                List<JumpPad> pads = new ArrayList<>();
                for (var element : island.getValue().getAsJsonArray()) {
                    JsonObject o = element.getAsJsonObject();
                    pads.add(new JumpPad(pos(o.getAsJsonArray("stand")), pos(o.getAsJsonArray("landing")),
                            o.get("flightMs").getAsLong(), o.get("launches").getAsInt(),
                            JumpPad.Certainty.valueOf(o.get("certainty").getAsString())));
                }
                byIsland.put(island.getKey(), pads);
            }
            if (root.has("transfers")) {
                for (var island : root.getAsJsonObject("transfers").entrySet()) {
                    List<TransferPad> pads = new ArrayList<>();
                    for (var element : island.getValue().getAsJsonArray()) {
                        JsonObject o = element.getAsJsonObject();
                        pads.add(new TransferPad(pos(o.getAsJsonArray("stand")), o.get("toIsland").getAsString(),
                                o.get("launches").getAsInt(),
                                JumpPad.Certainty.valueOf(o.get("certainty").getAsString())));
                    }
                    transfersByIsland.put(island.getKey(), pads);
                }
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Pads] could not read {}: {}", FILE, e.toString());
        }
    }

    private void save() {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        JsonObject islands = new JsonObject();
        for (var entry : byIsland.entrySet()) {
            JsonArray pads = new JsonArray();
            for (JumpPad pad : entry.getValue()) {
                JsonObject o = new JsonObject();
                o.add("stand", array(pad.stand()));
                o.add("landing", array(pad.landing()));
                o.addProperty("flightMs", pad.flightMs());
                o.addProperty("launches", pad.launches());
                o.addProperty("certainty", pad.certainty().name());
                pads.add(o);
            }
            islands.add(entry.getKey(), pads);
        }
        root.add("islands", islands);
        JsonObject transfers = new JsonObject();
        for (var entry : transfersByIsland.entrySet()) {
            JsonArray pads = new JsonArray();
            for (TransferPad pad : entry.getValue()) {
                JsonObject o = new JsonObject();
                o.add("stand", array(pad.stand()));
                o.addProperty("toIsland", pad.toIsland());
                o.addProperty("launches", pad.launches());
                o.addProperty("certainty", pad.certainty().name());
                pads.add(o);
            }
            transfers.add(entry.getKey(), pads);
        }
        root.add("transfers", transfers);
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), SBSFiles.GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Pads] could not write {}: {}", FILE, e.toString());
        }
    }

    private static JsonArray array(BlockPos pos) {
        JsonArray a = new JsonArray();
        a.add(pos.getX());
        a.add(pos.getY());
        a.add(pos.getZ());
        return a;
    }

    private static BlockPos pos(JsonArray a) {
        return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
    }

    private static String round(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
