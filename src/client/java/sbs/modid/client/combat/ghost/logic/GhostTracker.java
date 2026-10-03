/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.ghost.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.combat.ghost.render.GhostHighlight;
import sbs.modid.client.combat.mobhighlight.logic.MobHighlightTracker;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.helper.visual.logic.MistVisuals;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Picks the ghost to kill next while grinding The Mist, and hands the list to {@link GhostHighlight} to
 * draw.
 *
 * <p><b>The point of the module.</b> The nearest ghost is not the best ghost. A ghost five blocks
 * behind you costs a full turn before you can even swing, while one fifteen blocks ahead is already
 * in front of your crosshair - by the time the turn is done, the far one would have been dead. So
 * every candidate is scored in one currency, <i>blocks of walking</i>, and the cheapest one wins:
 *
 * <pre>
 *   cost = travel + turn + wall + contested
 * </pre>
 *
 * <ul>
 *   <li><b>travel</b> - horizontal distance, plus the height difference weighted separately:
 *       {@link #CLIMB_WEIGHT} for a ghost above you (jumping up the pit's ledges is the slow part)
 *       and {@link #DROP_WEIGHT} for one below (falling is nearly free). A straight 3D distance
 *       would rate a ghost on the ledge above your head as "one block away".</li>
 *   <li><b>turn</b> - the angle your crosshair has to swing, scaled by the configured cost of a
 *       full 180&deg; turn. This is the setting the whole module exists for: at the default 10, a
 *       ghost directly behind you has to be 10 blocks closer than one straight ahead to be worth
 *       turning around for. The angle is the real 3D angle between your look vector and the
 *       direction to the ghost, so a ghost far above you costs the swing up as well.</li>
 *   <li><b>wall</b> - a flat surcharge for a ghost you have no line of sight to, because the walk
 *       there is never the straight line the distance suggests.</li>
 *   <li><b>contested</b> - a surcharge for a ghost some other player is standing closer to. The
 *       Mist is a public lobby; a ghost someone else is already on is usually not yours.</li>
 * </ul>
 *
 * <p><b>Stickiness.</b> A pure per-tick minimum flickers: two ghosts a hair apart in cost trade the
 * highlight back and forth every time you move the mouse, and a tracer that snaps between targets is
 * worse than none. So the current pick is only dropped when another beats it by {@link
 * #SWITCH_MARGIN} blocks and it has been held for {@link #MIN_HOLD_MS}. It is dropped instantly when
 * it dies, leaves range or leaves the world - which is the normal case, since you just killed it.
 *
 * <p><b>What counts as a ghost.</b> A SkyBlock ghost is an invisible <i>charged creeper</i> - the
 * energy swirl is its whole visible body (the same fact {@link MistVisuals} dims). That is the
 * primary test, and it needs no nametag at all. Only when it finds nothing does a fallback pass look
 * for a mob whose nametag reads "Ghost", so a Hypixel change of entity would degrade rather than
 * break the module.
 */
public final class GhostTracker {

    private static final GhostTracker INSTANCE = new GhostTracker();

    /** The SkyBlock mob this module hunts, as its nametag spells it (fallback detection only). */
    private static final String GHOST_NAME = "ghost";

    /**
     * How a block of height counts against a block of walking. Climbing the Mist's ledges costs real
     * time (jumps, or a way around); dropping down costs almost none, so the two directions are not
     * the same journey and a plain 3D distance would treat them as one.
     */
    private static final double CLIMB_WEIGHT = 2.0;
    private static final double DROP_WEIGHT = 0.5;

    /** Scanning cadence in client ticks - 10 scans a second, far finer than you can react. */
    private static final int SCAN_EVERY = 2;

    /** How much cheaper a rival has to be before the highlight moves, in blocks. */
    private static final double SWITCH_MARGIN = 4.0;

    /** How long a pick is kept before any rival may take it, in milliseconds. */
    private static final long MIN_HOLD_MS = 300L;

    /** The tuning heartbeat's interval. */
    private static final long LOG_INTERVAL_MS = 10_000L;

    /** Entity id of the ghost currently marked, and when it was picked (for the hysteresis above). */
    private int chosenId = -1;
    private long chosenAt;

    private int tickCounter;
    private long lastLogAt;

    /** Whether anything is currently published, so a cleared state is only pushed once. */
    private boolean publishing;

    private GhostTracker() {
    }

    public static GhostTracker getInstance() {
        return INSTANCE;
    }

    /** One scored candidate: the cost that ranked it, plus the parts the label and the log show. */
    public record Ghost(LivingEntity mob, double cost, double distance, double turnDegrees,
                        boolean visible, boolean contested) {
    }

    private static SBSConfig.GhostHunterSettings cfg() {
        return ConfigManager.getInstance().get().ghostHunter;
    }

    /** Called every client tick; rescans on {@link #SCAN_EVERY} and republishes what to draw. */
    public void onClientTick() {
        SBSConfig.GhostHunterSettings cfg = cfg();
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (!cfg.enabled || level == null || player == null) {
            clear();
            return;
        }
        // The ghosts only exist in the pit, and a charged creeper anywhere else is not one of them.
        // The toggle is there so the highlight can be tried out (or used on a charged creeper in a
        // singleplayer world) without the location gate having an opinion.
        if (cfg.onlyInTheMist && !MistVisuals.inTheMist()) {
            clear();
            return;
        }
        if (++tickCounter < SCAN_EVERY) {
            return;
        }
        tickCounter = 0;

        List<LivingEntity> candidates = findGhosts(level, player, cfg);
        if (candidates.isEmpty()) {
            chosenId = -1;
            clear();
            logEmpty(level, player, cfg);
            return;
        }

        List<Ghost> scored = score(level, player, candidates, cfg);
        Ghost best = pick(scored);
        publish(scored, best);
        log(scored, best);
    }

    // ------------------------------------------------------------------ finding

    /**
     * Every ghost within range: charged creepers first, and only if there are none, mobs whose
     * nametag says "Ghost". Both passes are range-boxed, so the cost is a handful of entities however
     * busy the lobby is.
     */
    private static List<LivingEntity> findGhosts(ClientLevel level, LocalPlayer player,
                                                 SBSConfig.GhostHunterSettings cfg) {
        AABB area = player.getBoundingBox().inflate(Math.max(8, cfg.maxRange));
        List<LivingEntity> found = new ArrayList<>(
                level.getEntitiesOfClass(Creeper.class, area, c -> c.isAlive() && c.isPowered()));
        if (!found.isEmpty()) {
            return found;
        }
        // Fallback: the mob carries its name itself, or wears the usual floating nametag stand.
        for (LivingEntity mob : level.getEntitiesOfClass(LivingEntity.class, area,
                m -> m.isAlive() && m != player && !(m instanceof Player))) {
            if (mob instanceof ArmorStand stand) {
                var custom = stand.getCustomName();
                if (custom == null || !isGhostName(custom.getString())) {
                    continue;
                }
                LivingEntity below = MobHighlightTracker.mobBelow(level, stand);
                if (below != null && !found.contains(below)) {
                    found.add(below);
                }
                continue;
            }
            var custom = mob.getCustomName();
            if (custom != null && isGhostName(custom.getString()) && !found.contains(mob)) {
                found.add(mob);
            }
        }
        return found;
    }

    /** Whether a raw nametag names a Ghost, decoration and health readout stripped off. */
    private static boolean isGhostName(String rawName) {
        String name = MobHighlightTracker.mobNameInNametag(rawName);
        return name != null && name.toLowerCase(Locale.ROOT).equals(GHOST_NAME);
    }

    // ------------------------------------------------------------------ scoring

    /** Scores every candidate in the one currency the module ranks in: blocks of walking. */
    private static List<Ghost> score(ClientLevel level, LocalPlayer player,
                                     List<LivingEntity> candidates,
                                     SBSConfig.GhostHunterSettings cfg) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        double range = Math.max(8, cfg.maxRange);
        List<Ghost> scored = new ArrayList<>(candidates.size());
        for (LivingEntity mob : candidates) {
            Vec3 target = mob.getBoundingBox().getCenter();
            Vec3 offset = target.subtract(eye);
            double distance = offset.length();
            if (distance > range) {
                continue;
            }

            // Travel: flat distance, plus the climb or the drop at their own weights.
            double flat = Math.sqrt(offset.x * offset.x + offset.z * offset.z);
            double travel = flat + (offset.y > 0 ? offset.y * CLIMB_WEIGHT : -offset.y * DROP_WEIGHT);

            // Turn: the angle between where you are looking and where the ghost is, priced by the
            // "cost of a 180" setting. This is the whole reason the nearest ghost is not the answer.
            double turnDegrees = 0.0;
            if (distance > 1.0e-4) {
                double dot = offset.scale(1.0 / distance).dot(look);
                turnDegrees = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))));
            }
            double turn = turnDegrees / 180.0 * cfg.turnCost;

            boolean visible = player.hasLineOfSight(mob);
            boolean contested = cfg.contestedPenalty > 0 && isContested(level, player, mob);
            double cost = travel + turn
                    + (visible ? 0.0 : cfg.wallPenalty)
                    + (contested ? cfg.contestedPenalty : 0.0);
            scored.add(new Ghost(mob, cost, distance, turnDegrees, visible, contested));
        }
        return scored;
    }

    /**
     * Whether another player is standing closer to this ghost than you are. The Mist is a shared
     * lobby and a ghost with somebody already on it is usually somebody else's kill - worth avoiding,
     * not worth forbidding, so it is priced rather than filtered.
     */
    private static boolean isContested(ClientLevel level, LocalPlayer player, LivingEntity mob) {
        double mine = mob.distanceToSqr(player);
        for (Player other : level.players()) {
            if (other == player || !other.isAlive()) {
                continue;
            }
            if (mob.distanceToSqr(other) < mine) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ choosing

    /**
     * The cheapest candidate, with the switch hysteresis applied: the ghost already marked keeps the
     * mark unless a rival is {@link #SWITCH_MARGIN} blocks cheaper and the pick has stood for {@link
     * #MIN_HOLD_MS}. Without it the highlight strobes between two near-equal ghosts as the crosshair
     * moves, since the crosshair is itself an input to the cost.
     */
    private Ghost pick(List<Ghost> scored) {
        Ghost cheapest = null;
        Ghost current = null;
        for (Ghost ghost : scored) {
            if (cheapest == null || ghost.cost() < cheapest.cost()) {
                cheapest = ghost;
            }
            if (ghost.mob().getId() == chosenId) {
                current = ghost;
            }
        }
        if (cheapest == null) {
            chosenId = -1;
            return null;
        }
        long now = System.currentTimeMillis();
        if (current != null && current != cheapest
                && (now - chosenAt < MIN_HOLD_MS || cheapest.cost() > current.cost() - SWITCH_MARGIN)) {
            return current;   // the standing pick is still good enough - leave it alone
        }
        if (cheapest.mob().getId() != chosenId) {
            chosenId = cheapest.mob().getId();
            chosenAt = now;
        }
        return cheapest;
    }

    // ------------------------------------------------------------------ publishing

    private void publish(List<Ghost> scored, Ghost best) {
        GhostHighlight.setTargets(scored, best);
        publishing = true;
    }

    /** Drops everything drawn; cheap enough to call every tick, and only pushes on a real change. */
    private void clear() {
        if (!publishing) {
            return;
        }
        GhostHighlight.setTargets(List.of(), null);
        publishing = false;
    }

    // ------------------------------------------------------------------ tuning log

    /**
     * The {@code [SBS][Ghost]} heartbeat: what was found and why the marked one won. Ghost detection
     * rests on Hypixel still using charged creepers, and a cost model is only trustworthy once its
     * numbers have been read next to the pit they describe - both of which this line answers from the
     * instance log alone.
     */
    private void log(List<Ghost> scored, Ghost best) {
        long now = System.currentTimeMillis();
        if (now - lastLogAt < LOG_INTERVAL_MS) {
            return;
        }
        lastLogAt = now;
        if (best == null) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Ghost] {} ghost(s) in range, none picked",
                    scored.size());
            return;
        }
        SkyblockSimplifiedSBS.LOGGER.info(String.format(Locale.US,
                "[SBS][Ghost] %d in range; picked cost=%.1f (%.1fm, turn %.0f°%s%s);"
                        + " nearest cost=%.1f",
                scored.size(), best.cost(), best.distance(), best.turnDegrees(),
                best.visible() ? "" : ", walled", best.contested() ? ", contested" : "",
                nearestCost(scored)));
    }

    /** The cost of the ghost that is simply nearest - the number the naive "closest" rule would use. */
    private static double nearestCost(List<Ghost> scored) {
        Ghost nearest = null;
        for (Ghost ghost : scored) {
            if (nearest == null || ghost.distance() < nearest.distance()) {
                nearest = ghost;
            }
        }
        return nearest == null ? 0.0 : nearest.cost();
    }

    /**
     * The same heartbeat for the case that matters most when the module looks dead: in the pit, on,
     * and nothing found. Names what IS around instead, so "Hypixel stopped using charged creepers"
     * and "you are not where the ghosts are" read differently in the log.
     */
    private void logEmpty(ClientLevel level, LocalPlayer player, SBSConfig.GhostHunterSettings cfg) {
        long now = System.currentTimeMillis();
        if (now - lastLogAt < LOG_INTERVAL_MS) {
            return;
        }
        lastLogAt = now;
        List<String> sample = new ArrayList<>();
        AABB area = player.getBoundingBox().inflate(Math.max(8, cfg.maxRange));
        for (LivingEntity mob : level.getEntitiesOfClass(LivingEntity.class, area, LivingEntity::isAlive)) {
            if (sample.size() >= 5 || mob == player || mob instanceof Player) {
                continue;
            }
            var custom = mob.getCustomName();
            sample.add(mob.getType().getDescription().getString()
                    + (custom == null ? "" : " \"" + custom.getString().replaceAll("§.", "") + "\""));
        }
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Ghost] no ghosts within {} blocks (zone='{}'); nearby mobs: {}",
                cfg.maxRange, sbs.modid.client.core.location.SkyBlockLocation.describe(), sample);
    }
}
