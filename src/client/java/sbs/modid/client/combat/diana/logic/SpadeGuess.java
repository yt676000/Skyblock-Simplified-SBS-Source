/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.logic;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import sbs.modid.client.combat.diana.model.DianaParticleData;
import sbs.modid.client.combat.diana.model.GuessStage;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.helper.rift.model.Certainty;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the spade's ability was pointing: the arc it draws, fitted and extrapolated to the ground.
 *
 * <h2>The signal</h2>
 *
 * <p>Firing the ability traces the flight of an invisible projectile with a trail of particles. The
 * trail's packets share a particle type with the treasure-burrow marker and are told apart by their
 * speed alone, which is why the signature table matches the speed and why
 * {@code SPADE_TRAIL} exists as a role rather than being folded into the treasure one.
 *
 * <h2>Collection, and the two gates on it</h2>
 *
 * <p>Points are only collected inside a short window after the ability was used, and each new point
 * must be within a few blocks of the previous one and not identical to it. Both gates exist for the
 * same reason: the Hub is full of other people. Another player's ability, or a treasure burrow
 * across the plaza, spliced into the middle of this arc produces a fit that is confidently wrong,
 * and a confidently wrong guess is worse than none because the player walks to it.
 *
 * <h2>The maths, and how much of it we understand</h2>
 *
 * <p>The trail is treated as a parametric curve sampled by index - first point at zero, second at
 * one - and a cubic is fitted to each axis independently. The slope at zero is the launch direction.
 * From it comes an observed pitch, which is then treated as the <i>output</i> of a fixed distortion
 * and inverted by bisection to recover the pitch that produced it. That pitch gives a control-point
 * distance, the control-point distance gives a curve parameter, and evaluating the three fitted
 * cubics there gives the landing point.
 *
 * <p><b>The constants in that last paragraph are not understood.</b> They plainly encode how the
 * server draws the arc, but no derivation of them is recorded anywhere they appear. They therefore
 * live in {@link DianaParticleData.SpadeCurve} with a {@link Certainty} tag beside them rather than
 * as literals here, so a capture corrects them with a JSON edit. Until such a capture exists, this
 * whole feature is tagged {@code ESTIMATED} and says so where the player can see it.
 */
public final class SpadeGuess {

    private static final SpadeGuess INSTANCE = new SpadeGuess();

    /** How long after the ability fires the trail is still believed to belong to it. */
    private static final long WINDOW_MS = 3_000L;

    /** Furthest a new trail point may be from the previous one and still be the same arc. */
    private static final double MAX_STEP = 3.0;

    /** Below this, two points are the same point and the second adds nothing but a singular fit. */
    private static final double MIN_STEP = 1.0E-4;

    /** A cubic needs four samples. Fewer is not a worse fit, it is no fit. */
    private static final int MIN_POINTS = 4;

    /** Past this the arc has been drawn and the extra points are somebody else's. */
    private static final int MAX_POINTS = 64;

    /** Bisection steps when inverting the pitch. Far more than a double needs, and free. */
    private static final int BISECTION_STEPS = 100;

    /** Ticks between fits. The arc is drawn over about a second; a fifth of one is soon enough. */
    private static final int FIT_EVERY_TICKS = 4;

    private final List<Vec3> trail = new ArrayList<>();

    /** The trail's size at the last fit, so an unchanged trail is not refitted. */
    private int fittedSize;

    /** Ticks since the last fit, for {@link #FIT_EVERY_TICKS}. */
    private int ticksSinceFit;

    /** When the ability was last used. Zero means "not collecting". */
    private long abilityAt;

    /** The last landing point worked out, or {@code null}. */
    private volatile BlockPos guess;

    /** When {@link #guess} was worked out, so a stale one can be told from a live one. */
    private volatile long guessAt;

    /** How many digs the player has already put into the current guess, carried on promotion. */
    private int guessDigs;

    /** How far the last attempt got. Reported by {@code /sbs diana} - see {@link GuessStage}. */
    private volatile GuessStage stage = GuessStage.IDLE;

    /**
     * Trail particles that arrived while nothing was collecting.
     *
     * <p>The single most useful number here. A non-zero count with no guesses means the particles
     * are arriving and being recognised, and what failed is the <i>arming</i> - the right-click that
     * should have started the collection did not reach us. That is a wiring question, not a
     * constants question, and the two have completely different fixes.
     */
    private volatile long unclaimedTrail;

    private SpadeGuess() {
    }

    public static SpadeGuess getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.DianaSettings cfg() {
        return ConfigManager.getInstance().get().diana;
    }

    /** The live guess, or {@code null} when there is none. */
    public BlockPos guess() {
        return guess;
    }

    /** How sure anyone should be about {@link #guess}, given where its constants came from. */
    public Certainty certainty() {
        DianaParticleData data = DianaParticles.data();
        return data == null ? Certainty.UNKNOWN : data.spadeCurve.resolvedCertainty();
    }

    /**
     * The player used the spade's ability.
     *
     * <p>Called from the item-use mixin. Clears the previous arc rather than adding to it: two
     * interleaved arcs fitted as one curve produce an answer that is nowhere near either burrow,
     * and that failure looks exactly like a working feature pointing somewhere useless.
     */
    public void onAbilityUsed() {
        if (!cfg().enabled || !cfg().spadeGuess) {
            return;
        }
        trail.clear();
        fittedSize = 0;
        abilityAt = System.currentTimeMillis();
        stage = GuessStage.COLLECTING;
        unclaimedTrail = 0L;
    }

    /** One point of the arc. */
    public void onTrailParticle(double x, double y, double z) {
        if (!cfg().enabled || !cfg().spadeGuess) {
            return;
        }
        long now = System.currentTimeMillis();
        if (abilityAt == 0L || now - abilityAt > WINDOW_MS) {
            // Trail particles arriving with no ability behind them. Worth recording rather than
            // dropping silently: it is what "the ability fired but nothing armed the collection"
            // looks like from here, and that was a real wiring bug once already.
            unclaimedTrail++;
            return;
        }
        Vec3 point = new Vec3(x, y, z);
        if (!trail.isEmpty()) {
            Vec3 last = trail.get(trail.size() - 1);
            double step = last.distanceTo(point);
            if (step > MAX_STEP || step < MIN_STEP) {
                return;
            }
        }
        if (trail.size() >= MAX_POINTS) {
            return;
        }
        trail.add(point);
    }

    /**
     * Once per tick: refit the arc when it has grown and the last fit is {@link #FIT_EVERY_TICKS}
     * old. Off the packet path for the same reason as the arrow guess - a particle appends, the tick
     * fits - even though the trail's cap keeps this fit cheap.
     */
    public void onClientTick() {
        ticksSinceFit++;
        if (trail.size() == fittedSize || ticksSinceFit < FIT_EVERY_TICKS
                || !cfg().enabled || !cfg().spadeGuess) {
            return;
        }
        ticksSinceFit = 0;
        fittedSize = trail.size();
        BlockPos landing = extrapolate();
        if (landing != null) {
            guess = landing;
            guessAt = System.currentTimeMillis();
            guessDigs = 0;
            stage = GuessStage.READY;
            DianaDebug.getInstance().note("spade guess -> " + landing.getX() + " "
                    + landing.getY() + " " + landing.getZ() + " from " + trail.size() + " point(s)");
        }
    }

    /**
     * Fits the collected arc and extrapolates it to where the projectile lands.
     *
     * @return the burrow's block, or {@code null} when there is not enough to say
     */
    private BlockPos extrapolate() {
        if (trail.size() < MIN_POINTS) {
            stage(GuessStage.COLLECTING);
            return null;
        }
        DianaParticleData data = DianaParticles.data();
        if (data == null) {
            stage(GuessStage.FIT_FAILED);
            return null;
        }
        DianaParticleData.SpadeCurve curve = data.spadeCurve;

        int n = trail.size();
        double[] xs = new double[n];
        double[] ys = new double[n];
        double[] zs = new double[n];
        for (int i = 0; i < n; i++) {
            Vec3 point = trail.get(i);
            xs[i] = point.x;
            ys[i] = point.y;
            zs[i] = point.z;
        }
        double[] fitX = CubicFit.fit(xs);
        double[] fitY = CubicFit.fit(ys);
        double[] fitZ = CubicFit.fit(zs);
        if (fitX == null || fitY == null || fitZ == null) {
            stage(GuessStage.FIT_FAILED);
            return null;
        }

        double dx = CubicFit.slopeAt(fitX, 0.0);
        double dy = CubicFit.slopeAt(fitY, 0.0);
        double dz = CubicFit.slopeAt(fitZ, 0.0);
        double speed = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (speed < MIN_STEP) {
            stage(GuessStage.FIT_FAILED);
            return null;
        }

        double pitch = truePitch(observedPitch(dx, dy, dz), curve.pitchShift);
        double control = curve.controlScale * Math.sin(pitch - Math.PI) + curve.controlOffset;
        if (control < 0.0) {
            // The inversion landed somewhere the square root cannot follow. Refusing here is the
            // whole reason absence is the safe answer: a NaN propagated into a block position is a
            // marker at the world origin, which is a bug report rather than a missing feature.
            stage(GuessStage.CURVE_UNDEFINED);
            return null;
        }
        double parameter = curve.parameterScale * Math.sqrt(control) / speed;
        if (!Double.isFinite(parameter)) {
            stage(GuessStage.CURVE_UNDEFINED);
            return null;
        }

        double landX = CubicFit.valueAt(fitX, parameter);
        double landY = CubicFit.valueAt(fitY, parameter) - curve.landingDrop;
        double landZ = CubicFit.valueAt(fitZ, parameter);
        if (!Double.isFinite(landX) || !Double.isFinite(landY) || !Double.isFinite(landZ)) {
            stage(GuessStage.CURVE_UNDEFINED);
            return null;
        }
        BlockPos landing = BlockPos.containing(landX, landY, landZ);
        DianaParticleData.Bounds bounds = data.hubBounds;
        if (bounds != null && bounds.usable()
                && !bounds.contains(landing.getX(), landing.getY(), landing.getZ())) {
            stage(GuessStage.OUTSIDE_HUB);
            return null;
        }
        return landing;
    }

    /** The launch pitch as the fitted slope reports it, in radians, positive downward. */
    private static double observedPitch(double dx, double dy, double dz) {
        double flat = Math.sqrt(dx * dx + dz * dz);
        return -Math.atan2(dy, flat);
    }

    /**
     * Recovers the pitch the projectile was actually launched at from the one the trail shows.
     *
     * <p>The mapping being inverted is monotonic over the half-turn a launch pitch can occupy, so a
     * bisection over that interval converges on the answer without needing the inverse in closed
     * form - which is just as well, because nobody has written the forward form down either.
     */
    private static double truePitch(double observed, double shift) {
        double low = -Math.PI / 2.0;
        double high = Math.PI / 2.0;
        double guessPitch = observed;
        for (int i = 0; i < BISECTION_STEPS; i++) {
            double mapped = Math.atan2(Math.sin(guessPitch) - shift, Math.cos(guessPitch));
            if (mapped == observed) {
                return guessPitch;
            }
            if (mapped < observed) {
                low = guessPitch;
            } else {
                high = guessPitch;
            }
            guessPitch = (low + high) / 2.0;
        }
        return guessPitch;
    }

    /**
     * A real burrow, or a removal, has settled what is at this block.
     *
     * @return the dig count the guess had accumulated, so a promotion can carry it across; zero when
     *         this block was not the guess
     */
    public int onBlockResolved(BlockPos pos) {
        BlockPos current = guess;
        if (current == null || pos == null || !current.equals(pos)) {
            return 0;
        }
        int digs = guessDigs;
        guess = null;
        guessDigs = 0;
        return digs;
    }

    /** The player dug the block this guess points at. */
    public void onGuessDug() {
        guessDigs++;
    }

    /** Whether {@code pos} is the block this guess points at. */
    public boolean pointsAt(BlockPos pos) {
        BlockPos current = guess;
        return current != null && current.equals(pos);
    }

    /** World change, server hop, island change, or the player asking. */
    public void reset() {
        trail.clear();
        fittedSize = 0;
        abilityAt = 0L;
        guess = null;
        guessAt = 0L;
        guessDigs = 0;
        stage = GuessStage.IDLE;
        unclaimedTrail = 0L;
    }

    /**
     * One line for the debug readout.
     *
     * <p>Leads with the stage, and names the unclaimed-trail count whenever there is one, because
     * those two together separate the three ways this can produce nothing: the particles never
     * arrive (no count, IDLE), they arrive but nothing armed the collection (a count, still IDLE),
     * or they arrive and are collected and the maths declines (a stage that says which step).
     */
    public String status() {
        if (!cfg().spadeGuess) {
            return "off";
        }
        StringBuilder out = new StringBuilder(128);
        BlockPos current = guess;
        if (current == null) {
            out.append(trail.isEmpty()
                    ? "no arc collected - use the spade's ability"
                    : trail.size() + " trail point(s): " + stage.explanation());
        } else {
            long age = (System.currentTimeMillis() - guessAt) / 1000L;
            out.append("guess at ").append(current.getX()).append(' ').append(current.getY())
                    .append(' ').append(current.getZ())
                    .append(" (").append(age).append("s ago, ")
                    .append(certainty().displayName()).append(" constants)");
        }
        if (unclaimedTrail > 0) {
            out.append("\n  ").append(unclaimedTrail)
                    .append(" trail particle(s) arrived with nothing collecting - the ability's")
                    .append(" right-click is not reaching us");
        }
        return out.toString();
    }

    /** How far the last attempt got, for the status readout and for tests. */
    public GuessStage stage() {
        return stage;
    }

    /**
     * The trail, the last ability use and the current guess, for the guard's error report. The
     * "last echo data" of the brief: when the ability fired and what its arc has collected so far.
     */
    public JsonObject snapshot() {
        JsonObject out = new JsonObject();
        out.addProperty("stage", String.valueOf(stage));
        out.addProperty("abilityAt", abilityAt);
        out.add("trail", DianaSnapshot.points(trail));
        out.addProperty("guess", DianaSnapshot.pos(guess));
        out.addProperty("guessAt", guessAt);
        out.addProperty("guessDigs", guessDigs);
        out.addProperty("unclaimedTrail", unclaimedTrail);
        return out;
    }

    /** Records the stage, and logs it once per change while the debug switch is on. */
    private void stage(GuessStage next) {
        if (stage == next) {
            return;
        }
        stage = next;
        if (!next.working()) {
            DianaDebug.getInstance().note("spade guess stopped: " + next.explanation()
                    + " (" + trail.size() + " trail point(s))");
        }
    }
}
