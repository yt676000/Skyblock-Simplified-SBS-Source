/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.logic;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.combat.diana.model.ArrowBand;
import sbs.modid.client.combat.diana.model.DianaParticleData;
import sbs.modid.client.combat.diana.model.GuessStage;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The arrow the server draws out of a dug burrow, turned into a place to walk to.
 *
 * <h2>Three problems, in order</h2>
 *
 * <ol>
 *   <li><b>Find the arrow.</b> The particles arrive as a cloud with no structure and no ordering,
 *       mixed with whatever else is being drawn nearby. What is wanted is the straight run of points
 *       inside it.</li>
 *   <li><b>Work out which end is the head.</b> An arrow fitted backwards is a guess pointing exactly
 *       180 degrees wrong, delivered with the same confidence as a correct one.</li>
 *   <li><b>Turn a direction into a block.</b> The ray points at a burrow that may be hundreds of
 *       blocks away, through terrain that is not loaded, and lands on something that has to look
 *       like ground.</li>
 * </ol>
 *
 * <h2>The colour is the range, and it is in the offsets</h2>
 *
 * <p>These packets arrive in the directional form, where vanilla would read the three offsets as a
 * velocity. Hypixel puts a colour there instead, and the colour narrows how far away the burrow is.
 * So the signature that recognises an arrow deliberately does <b>not</b> match the offsets, and this
 * class reads them afterwards as a message.
 *
 * <p>The shipped band table is empty, because three sources describe those colours and no two agree.
 * With no band the candidates simply are not filtered by range: more of them to walk, none of them
 * confidently wrong. That is the degradation this feature is designed to have.
 *
 * <h2>Never on the packet path</h2>
 *
 * <p>A particle only appends to the cloud. The fit runs from {@link #onClientTick}, at most every
 * {@link #FIT_EVERY_TICKS} ticks and only when the cloud has grown since the last attempt. The first
 * version fitted on every particle, on the render thread, over a cloud that only cleared on success;
 * a cloud that never fitted made each packet cost more than the last until the client froze on the
 * first burrow of a chain (1.0.0-beta.10). The search itself lives in {@link ArrowShaft}.
 *
 * <h2>Scoring by angle, not by distance</h2>
 *
 * <p>A candidate's score is its perpendicular distance from the ray divided by its distance from the
 * ray's origin. Dividing is the whole trick: it turns an absolute miss into an angular one, so a
 * block a hundred blocks out is not punished for being three blocks off the line when a block ten
 * blocks out would have to be within centimetres. Without it, every guess lands at the player's
 * feet.
 */
public final class ArrowGuess {

    private static final ArrowGuess INSTANCE = new ArrowGuess();

    /** Neighbour count that marks the dense end - the head. */
    private static final int NEIGHBOURS_AT_HEAD = 4;

    /** Neighbour count that marks the sparse end - the tail. */
    private static final int NEIGHBOURS_AT_TAIL = 2;

    /** Below this the fitted shaft has no length to take a direction from. */
    private static final double COLLINEAR_EPSILON = ArrowShaft.COLLINEAR_EPSILON;

    /** How far from the burrow it was drawn out of an arrow particle may be. */
    private static final double NEAR_LAST_BURROW = 7.0;

    /** Both ends of the fitted line are dropped this far to reach the ground the arrow sits on. */
    private static final double ENDPOINT_DROP = 1.5;

    /** Scores within this of the best are all "equally good" and all become candidates. */
    private static final double SCORE_EPSILON = 1.0E-6;

    /** Scale factor that keeps the angular score in a readable range. Cosmetic; ratios are what matter. */
    private static final double SCORE_SCALE = 500_000.0;

    /**
     * Distinct points held before collection stops.
     *
     * <p>No capture has counted one arrow's particles yet, so this is reasoned rather than measured.
     * The shaft is {@link ArrowShaft#SHAFT_POINTS} points at most {@link ArrowShaft#STEP_TOLERANCE}
     * apart and the head adds a handful more - call one arrow fifty distinct positions, since a redraw
     * on the same coordinates is not held twice. 512 leaves room for ten arrows' worth of other dust
     * near the burrow, and keeps the search's worst case (every point a start, twenty steps, 27 cells
     * each) well under a millisecond. It was 4,000, which is what turned a fit that never succeeded
     * into a freeze.
     */
    public static final int MAX_POINTS = 512;

    /** Ticks between fit attempts. Packets arrive many per tick; the fit has no reason to. */
    public static final int FIT_EVERY_TICKS = 4;

    /** How long after a dig a full cloud with no fit is given before it is dropped (5 s). */
    public static final int GIVE_UP_TICKS = 100;

    /** How close the player must get, holding a spade, before a silent guess is called wrong. */
    private static final double DISPROVE_RADIUS = 30.0;

    /** A chain older than this is stale however plausible it still looks. */
    private static final long CHAIN_TTL_MS = 10 * 60 * 1000L;

    /** The accumulated arrow particles. */
    private final ArrowShaft cloud = new ArrowShaft();

    /**
     * Whether particles are being collected: from a dig until the cloud is given up on. Nothing is
     * collected before the first dig - the arrow is drawn out of a dug burrow, so dust seen before
     * one is somebody else's.
     */
    private boolean collecting;

    /** Ticks since the last dig, for {@link #GIVE_UP_TICKS}. */
    private int ticksSinceDig;

    /** Ticks since the last fit attempt, for {@link #FIT_EVERY_TICKS}. */
    private int ticksSinceAttempt;

    /** The cloud's size at the last attempt, so an unchanged cloud is not fitted twice. */
    private int attemptedSize;

    /** Whether any fit succeeded since the last dig, which decides whether giving up is news. */
    private boolean fittedSinceDig;

    /** The band the current cloud's colour named, or {@code null} when none is recorded. */
    private ArrowBand band;

    /** Live chains. Copy-on-write: the render pass reads this while packets and ticks write it. */
    private final List<GuessChain> chains = new CopyOnWriteArrayList<>();

    /** Where the last burrow the player dug was, so an arrow can be attributed to it. */
    private volatile BlockPos lastDug;

    /** Blocks the player has recently dug, so "grass turned to air" does not disqualify them. */
    private final Map<BlockPos, Long> recentlyDug = new LinkedHashMap<>();

    /** How long a dug block keeps being treated as valid ground. */
    private static final long RECENTLY_DUG_MS = 4_000L;

    /** How far the last attempt got. Reported by {@code /sbs diana} - see {@link GuessStage}. */
    private volatile GuessStage stage = GuessStage.IDLE;

    /** Whether the last fit failed, so the prompt fires once rather than once per packet. */
    private boolean promptedFailure;

    private ArrowGuess() {
    }

    public static ArrowGuess getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.DianaSettings cfg() {
        return ConfigManager.getInstance().get().diana;
    }

    /** Every live chain, front candidates first. */
    public List<GuessChain> chains() {
        return List.copyOf(chains);
    }

    /** The nearest live guess to the player, or {@code null}. */
    public BlockPos nearestGuess() {
        Player player = Minecraft.getInstance().player;
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (GuessChain chain : chains) {
            BlockPos candidate = chain.current();
            if (candidate == null) {
                continue;
            }
            double distance = player == null ? 0.0
                    : player.position().distanceToSqr(Vec3.atCenterOf(candidate));
            if (best == null || distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // Collection
    // ------------------------------------------------------------------

    /** One arrow particle, with the colour payload its offsets carry. */
    public void onArrowParticle(double x, double y, double z,
                                double offX, double offY, double offZ) {
        if (!cfg().enabled || !cfg().arrowGuess || !collecting || DianaGuard.arrowGuessDown()) {
            return;
        }
        Vec3 point = new Vec3(x, y, z);
        BlockPos origin = lastDug;
        if (origin != null && point.distanceTo(Vec3.atCenterOf(origin)) > NEAR_LAST_BURROW * 4) {
            // Far outside the neighbourhood of the burrow this arrow was drawn out of, so it belongs
            // to somebody else's. Generous on purpose - the arrow is long, and only its base is near
            // the burrow - but not so generous that two players standing together get spliced.
            return;
        }
        if (cloud.size() >= MAX_POINTS || !cloud.add(point)) {
            return;
        }
        ArrowBand matched = DianaParticles.bandFor(offX, offY, offZ);
        if (matched != null) {
            band = matched;
        }
    }

    /**
     * The player clicked a block with a spade. Remembered for {@link #plausibleBurrow}'s rule that
     * air counts as ground when the player just broke it, and as an interaction with a guess at that
     * block. Deliberately <b>not</b> a restart: players click on while the arrow is drawn, and a
     * restart per click threw the arrow's particles away (2026-10-04 capture: up to 18 clicks inside
     * one arrow).
     */
    public void onBlockClicked(BlockPos pos) {
        if (pos == null) {
            return;
        }
        recentlyDug.put(pos, System.currentTimeMillis());
        for (GuessChain chain : chains) {
            if (chain.pointsAt(pos)) {
                chain.recordDig();
            }
        }
    }

    /**
     * A dig was confirmed by its {@code (n/m)} chat line: the next arrow is drawn out of this burrow.
     * Called by {@link BurrowChat}, not by the click.
     */
    public void onBlockDug(BlockPos pos) {
        if (pos == null) {
            return;
        }
        lastDug = pos;
        recentlyDug.put(pos, System.currentTimeMillis());
        // A new burrow means a new arrow. The old cloud belongs to the previous one and fitting the
        // two together produces a line through both, which points at neither.
        cloud.clear();
        band = null;
        promptedFailure = false;
        collecting = true;
        ticksSinceDig = 0;
        ticksSinceAttempt = 0;
        attemptedSize = 0;
        fittedSinceDig = false;
        stage(GuessStage.COLLECTING);
    }

    // ------------------------------------------------------------------
    // The fit
    // ------------------------------------------------------------------

    /**
     * One pass over the collected cloud.
     *
     * <p>Every early return records <b>which stage declined</b> before it returns. That is not
     * instrumentation added for tidiness: this pipeline has five places it can refuse, all of them
     * are correct refusals, and without the stage the whole thing is indistinguishable from a
     * feature that is switched off. Nothing here has been verified against the live game, so "which
     * stage stopped" is the one question worth being able to answer.
     */
    private void attemptFit() {
        if (cloud.size() < ArrowShaft.SHAFT_POINTS) {
            stage(GuessStage.COLLECTING);
            return;
        }
        List<Vec3> line = cloud.findShaft();
        if (line.isEmpty()) {
            stage(GuessStage.NO_SHAFT);
            return;
        }
        Vec3[] ends = orientation(line);
        if (ends == null) {
            // The density test refused to say which end is the head. Rejecting is correct: a coin
            // flip here produces a guess pointing exactly backwards, which is worse than none
            // because it is indistinguishable from a good one until the player has walked it.
            stage(GuessStage.AMBIGUOUS_ENDS);
            return;
        }
        Vec3 base = ends[0].subtract(0.0, ENDPOINT_DROP, 0.0);
        Vec3 head = ends[1].subtract(0.0, ENDPOINT_DROP, 0.0);
        Vec3 direction = head.subtract(base);
        if (direction.lengthSqr() < COLLINEAR_EPSILON) {
            stage(GuessStage.DEGENERATE);
            return;
        }
        direction = direction.normalize();

        List<BlockPos> candidates = march(base, direction);
        if (candidates.isEmpty()) {
            stage(outsideHub(base) ? GuessStage.OUTSIDE_HUB : GuessStage.NO_LANDING);
            if (cfg().promptOnGuessFailure && !promptedFailure) {
                promptedFailure = true;
                DianaPrompts.guessFailed();
            }
            return;
        }
        GuessChain chain = new GuessChain(candidates);
        // Either way the arrow has been read; what is held now is a redraw of it, and fitting that
        // again only re-finds the same chain.
        cloud.clear();
        fittedSinceDig = true;
        for (GuessChain existing : chains) {
            if (existing.sameAs(chain)) {
                stage(GuessStage.READY);
                return;
            }
        }
        chains.add(chain);
        promptedFailure = false;
        stage(GuessStage.READY);
        DianaDebug.getInstance().note("arrow guess -> " + chain
                + (band == null ? " (no range band)" : " within " + band.describe()));
    }

    /**
     * Whether the ray started outside the Hub box, which makes {@link #march} refuse on its first
     * step and is worth telling apart from "the ray was fine and nothing on it was ground".
     *
     * <p>The two look identical from the outside and have completely different fixes: one is a wrong
     * box in a data file, the other is the ground test or the geometry.
     */
    private static boolean outsideHub(Vec3 origin) {
        DianaParticleData data = DianaParticles.data();
        DianaParticleData.Bounds bounds = data == null ? null : data.hubBounds;
        return bounds != null && bounds.usable() && !bounds.contains(origin.x, origin.y, origin.z);
    }

    /** Records the stage, and logs it once per change while the debug switch is on. */
    private void stage(GuessStage next) {
        if (stage == next) {
            return;
        }
        stage = next;
        if (!next.working()) {
            DianaDebug.getInstance().note("arrow guess stopped: " + next.explanation()
                    + " (" + cloud.size() + " particle(s) held)");
        }
    }

    /**
     * Which end of the run is the arrow's head.
     *
     * <p>The head is drawn with more particles than the shaft, so the point next to it has more
     * neighbours than the point next to the tail. Counting one in from each end rather than at the
     * ends themselves is what makes the difference measurable at all - an endpoint has neighbours on
     * one side only.
     *
     * @return {@code {base, head}}, or {@code null} when neither end looks like a head
     */
    private Vec3[] orientation(List<Vec3> run) {
        Vec3 nearStart = run.get(1);
        Vec3 nearEnd = run.get(run.size() - 2);
        int atStart = cloud.neighbours(nearStart);
        int atEnd = cloud.neighbours(nearEnd);

        if (atStart == NEIGHBOURS_AT_HEAD && atEnd == NEIGHBOURS_AT_TAIL) {
            return new Vec3[] {run.get(run.size() - 1), run.get(0)};
        }
        if (atStart == NEIGHBOURS_AT_TAIL && atEnd == NEIGHBOURS_AT_HEAD) {
            return new Vec3[] {run.get(0), run.get(run.size() - 1)};
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Ray to blocks
    // ------------------------------------------------------------------

    /**
     * Walks the ray through the Hub and returns the blocks that could be the burrow, best first.
     *
     * <p>One axis is marched rather than three: stepping along the axis the ray travels furthest in
     * samples the line densely enough to hit every block it passes through, without the bookkeeping
     * of a full voxel traversal for a search whose answer is a block-sized target hundreds of blocks
     * away.
     */
    private List<BlockPos> march(Vec3 origin, Vec3 direction) {
        DianaParticleData data = DianaParticles.data();
        DianaParticleData.Bounds bounds = data == null ? null : data.hubBounds;
        double reach = band != null ? band.max : 600.0;

        int axis = dominantAxis(direction);
        double step = component(direction, axis);
        if (Math.abs(step) < 1.0E-3) {
            return List.of();
        }

        Map<BlockPos, double[]> scored = new LinkedHashMap<>();
        int steps = (int) Math.ceil(reach * Math.abs(step) + reach);
        for (int i = 1; i <= steps; i++) {
            double along = i / Math.abs(step);
            if (along > reach) {
                break;
            }
            Vec3 point = origin.add(direction.scale(along));
            if (bounds != null && bounds.usable() && !bounds.contains(point.x, point.y, point.z)) {
                break;
            }
            BlockPos block = BlockPos.containing(point);
            if (scored.containsKey(block) || !plausibleBurrow(block, data)) {
                continue;
            }
            Vec3 centre = Vec3.atCenterOf(block);
            double fromOrigin = centre.subtract(origin).dot(direction);
            if (fromOrigin <= 0.0) {
                continue;
            }
            double perpendicular = centre.subtract(origin).subtract(direction.scale(fromOrigin)).length();
            scored.put(block, new double[] {perpendicular * SCORE_SCALE / fromOrigin, fromOrigin});
        }
        if (scored.isEmpty()) {
            return List.of();
        }

        double bestScore = scored.values().stream().mapToDouble(v -> v[0]).min().orElse(0.0);
        List<Map.Entry<BlockPos, double[]>> tied = new ArrayList<>();
        for (Map.Entry<BlockPos, double[]> entry : scored.entrySet()) {
            if (Math.abs(entry.getValue()[0] - bestScore) <= SCORE_EPSILON
                    && (band == null || band.contains(entry.getValue()[1]))) {
                tied.add(entry);
            }
        }
        tied.sort(Comparator.comparingDouble(e -> e.getValue()[1]));

        List<BlockPos> out = new ArrayList<>(tied.size());
        for (Map.Entry<BlockPos, double[]> entry : tied) {
            out.add(entry.getKey());
        }
        return out;
    }

    /** The axis the ray travels furthest along, which is the one worth stepping in. */
    private static int dominantAxis(Vec3 direction) {
        double ax = Math.abs(direction.x);
        double ay = Math.abs(direction.y);
        double az = Math.abs(direction.z);
        if (ax >= ay && ax >= az) {
            return 0;
        }
        return ay >= az ? 1 : 2;
    }

    private static double component(Vec3 vector, int axis) {
        return switch (axis) {
            case 0 -> vector.x;
            case 1 -> vector.y;
            default -> vector.z;
        };
    }

    /**
     * Whether a block could be a burrow.
     *
     * <p>An unloaded chunk answers <b>yes</b>. Refusing what cannot be seen would refuse most of the
     * Hub, and the arrow's whole purpose is pointing at somewhere the player is not. A guess in an
     * unloaded chunk is re-checked as the player walks toward it and drops out of the chain if it
     * turns out to be a roof.
     */
    private boolean plausibleBurrow(BlockPos pos, DianaParticleData data) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft == null ? null : minecraft.level;
        if (level == null) {
            return false;
        }
        if (!level.hasChunkAt(pos)) {
            return true;
        }
        String ground = blockId(level, pos);
        String above = blockId(level, pos.above());
        if (data != null && !data.allowedGround(ground)) {
            return false;
        }
        if ("minecraft:air".equals(ground) && !wasRecentlyDug(pos)) {
            // Air is only ground when the player made it so themselves a moment ago; otherwise it is
            // the sky above a hillside, which every long ray passes through a great deal of.
            return false;
        }
        return data == null || data.allowedAbove(above);
    }

    private boolean wasRecentlyDug(BlockPos pos) {
        Long at = recentlyDug.get(pos);
        if (at == null) {
            return false;
        }
        if (System.currentTimeMillis() - at <= RECENTLY_DUG_MS) {
            return true;
        }
        recentlyDug.remove(pos);
        return false;
    }

    private static String blockId(ClientLevel level, BlockPos pos) {
        var key = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock());
        return key == null ? "" : key.toString();
    }

    // ------------------------------------------------------------------
    // Upkeep
    // ------------------------------------------------------------------

    /**
     * Once per tick: fit the cloud when that is due, and drop chains whose live candidate has been
     * disproved.
     *
     * <p>Two ways a candidate is disproved. Its block has stopped looking like ground - the chunk
     * loaded and it turned out to be a roof. Or the player has walked within reach of it carrying a
     * spade and no burrow has announced itself, which for a burrow that close is conclusive.
     */
    public void onClientTick() {
        if (!cfg().enabled || !cfg().arrowGuess) {
            return;
        }
        if (collecting && !DianaGuard.arrowGuessDown()) {
            fitWhenDue();
        }
        if (chains.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        DianaParticleData data = DianaParticles.data();
        boolean holdingSpade = DianaEvent.heldSpadeTier() != null;
        long now = System.currentTimeMillis();

        for (GuessChain chain : chains) {
            if (now - chain.createdAt() > CHAIN_TTL_MS) {
                chains.remove(chain);
                continue;
            }
            BlockPos candidate = chain.current();
            if (candidate == null) {
                chains.remove(chain);
                continue;
            }
            boolean disproved = !plausibleBurrow(candidate, data);
            if (!disproved && holdingSpade && player != null
                    && !BurrowStore.getInstance().has(candidate)
                    && player.position().distanceTo(Vec3.atCenterOf(candidate)) < DISPROVE_RADIUS) {
                disproved = true;
            }
            if (disproved && !chain.advance()) {
                chains.remove(chain);
            }
        }
    }

    /** The throttled fit, and giving up on a cloud that filled without one. */
    private void fitWhenDue() {
        ticksSinceDig++;
        ticksSinceAttempt++;
        if (ticksSinceAttempt >= FIT_EVERY_TICKS && cloud.size() != attemptedSize) {
            ticksSinceAttempt = 0;
            attemptFit();
            attemptedSize = cloud.size();
        }
        if (cloud.size() >= MAX_POINTS && ticksSinceDig >= GIVE_UP_TICKS) {
            giveUp();
        }
    }

    /**
     * The cloud is full, the dig is seconds old and no arrow came out of it: drop it and stop
     * collecting until the next dig. A full cloud admits nothing new, so carrying on would only
     * keep holding whatever filled it.
     */
    private void giveUp() {
        int held = cloud.size();
        cloud.clear();
        attemptedSize = 0;
        collecting = false;
        if (fittedSinceDig) {
            // The arrow was read; what filled the cloud since was redraws and other dust.
            return;
        }
        GuessStage last = stage;
        stage(GuessStage.GAVE_UP);
        try {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Diana] arrow guess gave up: {} particle(s) held {} ticks"
                    + " after the dig, last stage {} - waiting for the next dig", held, ticksSinceDig, last);
        } catch (RuntimeException | LinkageError ignored) {
            // No logger (unit tests without one): the stage above is what the readout shows.
        }
    }

    /**
     * A real burrow, or a removal, has settled what is at this block.
     *
     * @return the dig count the chain had accumulated there, for carrying across a promotion
     */
    public int onBlockResolved(BlockPos pos) {
        int carried = 0;
        for (GuessChain chain : chains) {
            if (chain.pointsAt(pos)) {
                carried = Math.max(carried, chain.digs());
                if (!chain.advance()) {
                    chains.remove(chain);
                }
            } else if (chain.contains(pos)) {
                // A runner-up has turned out to be a real burrow. The chain keeps its own live
                // candidate - one of its alternatives being right does not make the front one wrong.
                DianaDebug.getInstance().note("a runner-up became a real burrow");
            }
        }
        return carried;
    }

    /** World change, server hop, island change, or the player asking. */
    public void reset() {
        cloud.clear();
        chains.clear();
        recentlyDug.clear();
        band = null;
        lastDug = null;
        promptedFailure = false;
        collecting = false;
        ticksSinceDig = 0;
        ticksSinceAttempt = 0;
        attemptedSize = 0;
        fittedSinceDig = false;
        stage = GuessStage.IDLE;
    }

    /**
     * One line for the debug readout, leading with the stage.
     *
     * <p>The stage comes first because it is the answer to the question that brings anyone here.
     * "37 particles held, 0 chains" says nothing on its own; "a shaft was found but neither end
     * looked like the head" names the constant to go and check.
     */
    public String status() {
        if (!cfg().arrowGuess) {
            return "off";
        }
        if (DianaGuard.arrowGuessDown()) {
            return "paused for this session after two slow hooks - /sbs diana clear";
        }
        StringBuilder out = new StringBuilder(128);
        out.append(stage.explanation());
        out.append(" - ").append(cloud.size()).append(" particle(s) held, ")
                .append(chains.size()).append(" chain(s)");
        if (band != null) {
            out.append(", range ").append(band.describe());
        } else {
            out.append(", no range band recorded");
        }
        for (GuessChain chain : chains) {
            out.append("\n  ").append(chain);
        }
        return out.toString();
    }

    /** How far the last attempt got, for the status readout and for tests. */
    public GuessStage stage() {
        return stage;
    }

    /** The particle cloud, the band, every candidate chain and the dig memory, for the guard's report. */
    public JsonObject snapshot() {
        JsonObject out = new JsonObject();
        out.addProperty("stage", String.valueOf(stage));
        out.addProperty("band", band == null ? null : band.describe());
        out.addProperty("lastDug", DianaSnapshot.pos(lastDug));
        out.addProperty("promptedFailure", promptedFailure);
        out.addProperty("collecting", collecting);
        out.addProperty("ticksSinceDig", ticksSinceDig);
        out.add("cloud", DianaSnapshot.points(cloud.points()));
        JsonArray guesses = new JsonArray();
        for (GuessChain chain : chains) {
            JsonObject c = new JsonObject();
            c.addProperty("current", DianaSnapshot.pos(chain.current()));
            JsonArray remaining = new JsonArray();
            for (BlockPos pos : chain.remaining()) {
                if (remaining.size() >= DianaSnapshot.MAX_ENTRIES) {
                    break;
                }
                remaining.add(DianaSnapshot.pos(pos));
            }
            c.add("remaining", remaining);
            c.addProperty("digs", chain.digs());
            c.addProperty("createdAt", chain.createdAt());
            guesses.add(c);
        }
        out.add("chains", guesses);
        JsonObject dug = new JsonObject();
        recentlyDug.forEach((pos, at) -> dug.addProperty(DianaSnapshot.pos(pos), at));
        out.add("recentlyDug", dug);
        return out;
    }
}
