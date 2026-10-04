/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.logic;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.Vec3;
import sbs.modid.client.combat.diana.model.DianaParticleData;
import sbs.modid.client.combat.diana.model.GuessStage;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.helper.rift.model.Certainty;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the spade's Echo points: the trail's direction, and the distance its notes give away.
 *
 * <h2>The signal (captured 2026-10-04)</h2>
 *
 * <p>Echo (right click with the spade) draws a trail of points out from the player toward a burrow,
 * one point every two ticks. Each point is three particle packets - {@code dripping_lava} at speed
 * -0.5, which is the {@code SPADE_TRAIL} signature this collects, plus a firework and an enchant
 * packet - and one {@code block.note_block.harp} sound at the same place. The trail curves toward
 * the burrow as it goes and stops after at most about forty points, often long before reaching it.
 *
 * <h2>The rule</h2>
 *
 * <p>The harp's pitch starts near 0.5 and rises by a fixed step per point, and that step is
 * inversely proportional to how far the burrow is from the trail's first point:
 * {@code distance = scale / step + offset}, with both numbers in {@link DianaParticleData.EchoDistance}.
 * The direction is the chord from the first trail point to the latest one, flattened. Replayed over
 * the eleven Echoes of the capture whose burrow was then dug, the finished trail puts the guess 1-6
 * blocks from it up to 200 blocks out, 11 and 23 blocks off at 240 and 166. The first few points
 * curve and give a poor direction, so the guess appears after {@link #MIN_POINTS} points and keeps
 * moving onto the burrow as the trail grows.
 *
 * <p>This replaces a cubic fit and an extrapolation whose five constants came from another client's
 * description of the old spade ability; the same capture had the player marking its guesses wrong.
 *
 * <h2>The gates</h2>
 *
 * <p>Points and notes are only collected in a window after the ability fires, each point within a
 * few blocks of the previous one, each note within a few blocks of the trail. The Hub is full of
 * other players using the same ability, and their notes spliced into this trail would change the
 * step - which is the distance.
 */
public final class SpadeGuess {

    private static final SpadeGuess INSTANCE = new SpadeGuess();

    /** How long after the ability the trail still belongs to it. The longest captured ran 78 ticks. */
    private static final long WINDOW_MS = 5_000L;

    /** Furthest a new trail point may be from the previous one and still be the same trail. */
    private static final double MAX_STEP = 3.0;

    /** Below this, two points are the same point and add nothing. */
    private static final double MIN_STEP = 1.0E-4;

    /** Furthest a harp note may be from the latest trail point and still be this trail's. */
    private static final double NOTE_RADIUS = 3.0;

    /** Points and notes before a guess is shown. Fewer give a direction tens of degrees off. */
    static final int MIN_POINTS = 6;

    /** Past this the trail has been drawn and extra points are somebody else's. */
    private static final int MAX_POINTS = 64;

    /** A step this small would put the burrow thousands of blocks away; it is not a reading. */
    private static final double MIN_PITCH_STEP = 1.0E-3;

    /**
     * A right click this soon after the last trail point does not start a new trail. The capture has
     * players clicking again while a trail is still being drawn ("This ability is on cooldown");
     * restarting there would make a mid-trail point the "first" one, and the distance is measured
     * from the first.
     */
    private static final long BUSY_MS = 250L;

    /** Ticks between estimates. A point arrives every two ticks. */
    private static final int FIT_EVERY_TICKS = 4;

    /** How far up and down from the trail's start the ground under a guess is searched for. */
    private static final int GROUND_SEARCH_UP = 12;
    private static final int GROUND_SEARCH_DOWN = 30;

    private final List<Vec3> trail = new ArrayList<>();

    /** The harp pitches belonging to {@link #trail}, in arrival order. */
    private final List<Double> notes = new ArrayList<>();

    /** The trail's size at the last estimate, so an unchanged trail is not re-estimated. */
    private int fittedSize;

    private int ticksSinceFit;

    /** When the ability was last used. Zero means "not collecting". */
    private long abilityAt;

    /** When the last trail point was kept. */
    private long lastPointAt;

    /** The live guess, or {@code null}. */
    private volatile BlockPos guess;

    private volatile long guessAt;

    /** The distance the notes gave for {@link #guess}, for the readout. */
    private volatile double guessDistance;

    /** How many digs the player has already put into the current guess, carried on promotion. */
    private int guessDigs;

    private volatile GuessStage stage = GuessStage.IDLE;

    /**
     * Trail particles that arrived while nothing was collecting. A non-zero count with no guesses
     * means the trail arrives but the right click that should start collecting did not reach us.
     */
    private volatile long unclaimedTrail;

    /** What one estimate produced: where on the flat, and how far from the trail's start. */
    record Estimate(double x, double z, double distance, double step) {
    }

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
        return data == null || data.echo == null ? Certainty.UNKNOWN : data.echo.resolvedCertainty();
    }

    /**
     * The player used the spade's ability. Starts a new trail rather than adding to the last one:
     * two trails read as one give a step, and therefore a distance, belonging to neither.
     */
    public void onAbilityUsed() {
        if (!cfg().enabled || !cfg().spadeGuess) {
            return;
        }
        if (collecting() && System.currentTimeMillis() - lastPointAt < BUSY_MS) {
            return;
        }
        trail.clear();
        notes.clear();
        fittedSize = 0;
        abilityAt = System.currentTimeMillis();
        stage = GuessStage.COLLECTING;
        unclaimedTrail = 0L;
    }

    /** One point of the trail. */
    public void onTrailParticle(double x, double y, double z) {
        if (!cfg().enabled || !cfg().spadeGuess) {
            return;
        }
        if (!collecting()) {
            unclaimedTrail++;
            return;
        }
        Vec3 point = new Vec3(x, y, z);
        if (!trail.isEmpty()) {
            double step = trail.get(trail.size() - 1).distanceTo(point);
            if (step > MAX_STEP || step < MIN_STEP) {
                return;
            }
        }
        if (trail.size() < MAX_POINTS) {
            trail.add(point);
            lastPointAt = System.currentTimeMillis();
        }
    }

    /**
     * One harp note, from the sound packet. Kept when it sits on this trail: within
     * {@link #NOTE_RADIUS} of the latest point, or - for the note that arrives in the same tick as
     * the first point, possibly before it - the very first note of an empty trail.
     */
    public void onEchoNote(double x, double y, double z, float pitch) {
        if (!cfg().enabled || !cfg().spadeGuess || !collecting() || notes.size() >= MAX_POINTS) {
            return;
        }
        if (trail.isEmpty()) {
            if (notes.isEmpty()) {
                notes.add((double) pitch);
            }
            return;
        }
        if (trail.get(trail.size() - 1).distanceTo(new Vec3(x, y, z)) <= NOTE_RADIUS) {
            notes.add((double) pitch);
        }
    }

    private boolean collecting() {
        return abilityAt != 0L && System.currentTimeMillis() - abilityAt <= WINDOW_MS;
    }

    /** Once per tick: re-estimate when the trail has grown, every {@link #FIT_EVERY_TICKS} ticks. */
    public void onClientTick() {
        ticksSinceFit++;
        if (trail.size() == fittedSize || ticksSinceFit < FIT_EVERY_TICKS
                || !cfg().enabled || !cfg().spadeGuess) {
            return;
        }
        ticksSinceFit = 0;
        fittedSize = trail.size();
        BlockPos landing = locate();
        if (landing != null) {
            if (!landing.equals(guess)) {
                guessDigs = 0;
            }
            guess = landing;
            guessAt = System.currentTimeMillis();
            stage = GuessStage.READY;
            DianaDebug.getInstance().note("echo guess -> " + landing.getX() + " " + landing.getY() + " "
                    + landing.getZ() + " at " + Math.round(guessDistance) + " blocks from "
                    + trail.size() + " point(s), " + notes.size() + " note(s)");
        }
    }

    /** The estimate turned into a block: the ground at the estimated spot, inside the Hub. */
    private BlockPos locate() {
        DianaParticleData data = DianaParticles.data();
        if (data == null || data.echo == null) {
            stage(GuessStage.FIT_FAILED);
            return null;
        }
        if (trail.size() < MIN_POINTS || notes.size() < MIN_POINTS) {
            stage(GuessStage.COLLECTING);
            return null;
        }
        Estimate estimate = estimate(trail, notes, data.echo.scale, data.echo.offset);
        if (estimate == null) {
            stage(pitchStep(notes) < MIN_PITCH_STEP ? GuessStage.NO_PITCH : GuessStage.FIT_FAILED);
            return null;
        }
        guessDistance = estimate.distance();
        BlockPos landing = ground(estimate.x(), estimate.z(), trail.get(0).y, data);
        DianaParticleData.Bounds bounds = data.hubBounds;
        if (bounds != null && bounds.usable()
                && !bounds.contains(landing.getX(), landing.getY(), landing.getZ())) {
            stage(GuessStage.OUTSIDE_HUB);
            return null;
        }
        return landing;
    }

    /**
     * Where the burrow is on the flat, from the trail and its notes; {@code null} when the trail has
     * no direction or the notes do not rise. Pure, so the capture can be replayed against it.
     */
    static Estimate estimate(List<Vec3> trail, List<Double> notes, double scale, double offset) {
        if (trail.size() < 2 || notes.size() < 2) {
            return null;
        }
        double step = pitchStep(notes);
        if (!(step >= MIN_PITCH_STEP)) {
            return null;
        }
        Vec3 first = trail.get(0);
        Vec3 last = trail.get(trail.size() - 1);
        double dx = last.x - first.x;
        double dz = last.z - first.z;
        double length = Math.hypot(dx, dz);
        if (length < 0.5) {
            return null;
        }
        double distance = scale / step + offset;
        return new Estimate(first.x + dx / length * distance, first.z + dz / length * distance,
                distance, step);
    }

    /**
     * The per-note pitch step, as the least-squares slope of pitch against note index. The first
     * notes of a trail step unevenly; the slope uses all of them rather than the two ends.
     */
    static double pitchStep(List<Double> notes) {
        int n = notes.size();
        if (n < 2) {
            return 0.0;
        }
        double meanIndex = (n - 1) / 2.0;
        double meanPitch = 0.0;
        for (double pitch : notes) {
            meanPitch += pitch / n;
        }
        double covariance = 0.0;
        double variance = 0.0;
        for (int i = 0; i < n; i++) {
            covariance += (i - meanIndex) * (notes.get(i) - meanPitch);
            variance += (i - meanIndex) * (i - meanIndex);
        }
        return covariance / variance;
    }

    /**
     * The burrow's block at a spot: the highest block in the column, near the trail's height, that
     * the data allows as burrow ground with allowed cover on top. Unloaded chunks and columns with no
     * such block answer two below the trail's start, which is where captured burrows sat.
     */
    private static BlockPos ground(double x, double z, double fromY, DianaParticleData data) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int start = (int) Math.floor(fromY);
        BlockPos fallback = new BlockPos(bx, start - 2, bz);
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft == null ? null : minecraft.level;
        if (level == null || !level.hasChunkAt(fallback)) {
            return fallback;
        }
        for (int y = start + GROUND_SEARCH_UP; y >= start - GROUND_SEARCH_DOWN; y--) {
            BlockPos pos = new BlockPos(bx, y, bz);
            String block = blockId(level, pos);
            if (!"minecraft:air".equals(block) && data.allowedGround(block)
                    && data.allowedAbove(blockId(level, pos.above()))) {
                return pos;
            }
        }
        return fallback;
    }

    private static String blockId(ClientLevel level, BlockPos pos) {
        return String.valueOf(BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()));
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
        notes.clear();
        fittedSize = 0;
        abilityAt = 0L;
        lastPointAt = 0L;
        guess = null;
        guessAt = 0L;
        guessDistance = 0.0;
        guessDigs = 0;
        stage = GuessStage.IDLE;
        unclaimedTrail = 0L;
    }

    /**
     * One line for the debug readout. Leads with the stage, and names the unclaimed-trail count
     * whenever there is one: no count and no guess means the trail never arrived; a count means the
     * right click did not reach us; a stage means the trail arrived and the estimate declined.
     */
    public String status() {
        if (!cfg().spadeGuess) {
            return "off";
        }
        StringBuilder out = new StringBuilder(128);
        BlockPos current = guess;
        if (current == null) {
            out.append(trail.isEmpty()
                    ? "no trail collected - use the spade's Echo"
                    : trail.size() + " trail point(s), " + notes.size() + " note(s): " + stage.explanation());
        } else {
            long age = (System.currentTimeMillis() - guessAt) / 1000L;
            out.append("guess at ").append(current.getX()).append(' ').append(current.getY())
                    .append(' ').append(current.getZ())
                    .append(", ").append(Math.round(guessDistance)).append(" blocks out (")
                    .append(age).append("s ago, ").append(certainty().displayName()).append(" rule)");
        }
        if (unclaimedTrail > 0) {
            out.append("\n  ").append(unclaimedTrail)
                    .append(" trail particle(s) arrived with nothing collecting - the Echo")
                    .append(" right-click is not reaching us");
        }
        return out.toString();
    }

    /** How far the last attempt got, for the status readout and for tests. */
    public GuessStage stage() {
        return stage;
    }

    /** The trail, its notes and the current guess, for the guard's error report. */
    public JsonObject snapshot() {
        JsonObject out = new JsonObject();
        out.addProperty("stage", String.valueOf(stage));
        out.addProperty("abilityAt", abilityAt);
        out.add("trail", DianaSnapshot.points(trail));
        out.addProperty("notes", notes.toString());
        out.addProperty("guess", DianaSnapshot.pos(guess));
        out.addProperty("guessAt", guessAt);
        out.addProperty("guessDistance", DianaSnapshot.num(guessDistance));
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
            DianaDebug.getInstance().note("echo guess stopped: " + next.explanation()
                    + " (" + trail.size() + " trail point(s), " + notes.size() + " note(s))");
        }
    }
}
