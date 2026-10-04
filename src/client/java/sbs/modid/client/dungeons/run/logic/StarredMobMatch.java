/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.run.logic;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.Predicate;

/**
 * The two decisions behind a starred-mob box, kept free of the level so they are unit-tested:
 * which body a ✯ nametag stand belongs to, and whether that body can be seen.
 *
 * <p><b>Owner of a tag.</b> A nametag floats just above its mob's head, so the owner is the
 * candidate whose <i>top</i> is closest under the tag, not the one whose centre is nearest - the
 * centre test picked the wrong body whenever a tag hung over two mobs, or over a tall mob standing
 * next to a short one. Horizontal distance only breaks near-ties. A real player is never the owner;
 * a Hypixel player-model mob (a fake player, see {@code RealPlayers}) is a candidate like any other.
 *
 * <p><b>Visibility.</b> The box is still drawn only for a mob the player can actually see; the
 * change is what counts as seeing it. One eye-to-eye ray reported a mob half behind a slab, a fence
 * or a low wall as hidden. Now the mob is visible when <i>any</i> of a few points on its bounding
 * box - its eye, head, centre, feet and the four upper corners - has an unobstructed ray from the
 * player's eye. A mob with every one of those rays blocked, i.e. fully behind a wall, stays unboxed.
 */
public final class StarredMobMatch {

    /** Why a stand ended up with or without a box - also the probe's reason text. */
    public enum Outcome {
        BOXED("boxed"),
        NO_MOB("no mob in box"),
        REAL_PLAYER("real player"),
        NO_LINE_OF_SIGHT("no line of sight");

        private final String reason;

        Outcome(String reason) {
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    /**
     * One body inside a stand's search box.
     *
     * @param topGap     absolute vertical distance between the tag and the top of the body
     * @param horizontal horizontal distance between the stand and the body's centre
     * @param realPlayer the body is a real player (local, or listed in the player list)
     */
    public record Candidate<T>(T mob, double topGap, double horizontal, boolean realPlayer) {
    }

    /** The picked body, or null with {@link Outcome#NO_MOB} / {@link Outcome#REAL_PLAYER}. */
    public record Pick<T>(T mob, Outcome outcome) {
    }

    /**
     * Weight of horizontal distance against the vertical gap. At the 1.25-block search radius the
     * horizontal term is worth at most ~0.3 blocks of height, so it settles ties between bodies of
     * the same height and never overrides a clearly closer top.
     */
    static final double HORIZONTAL_WEIGHT = 0.25;

    /** Inset of the corner samples, so a corner ray does not graze the block the mob touches. */
    private static final double CORNER_INSET = 0.05;

    /** Lift / drop of the foot and head samples off the box faces, for the same reason. */
    private static final double FACE_INSET = 0.1;

    private StarredMobMatch() {
    }

    /** The owner of a tag among {@code candidates}: nearest top under the tag, never a real player. */
    public static <T> Pick<T> pick(List<Candidate<T>> candidates) {
        Candidate<T> best = null;
        double bestScore = Double.MAX_VALUE;
        boolean sawRealPlayer = false;
        for (Candidate<T> candidate : candidates) {
            if (candidate.realPlayer()) {
                sawRealPlayer = true;
                continue;
            }
            double score = candidate.topGap() + HORIZONTAL_WEIGHT * candidate.horizontal();
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        if (best != null) {
            return new Pick<>(best.mob(), Outcome.BOXED);
        }
        return new Pick<>(null, sawRealPlayer ? Outcome.REAL_PLAYER : Outcome.NO_MOB);
    }

    /**
     * The points tested for visibility, cheapest-to-succeed first: the mob's eye (the old single
     * ray), head, centre, feet, then the four upper corners.
     */
    public static List<Vec3> samplePoints(AABB box, double eyeY) {
        double cx = (box.minX + box.maxX) / 2;
        double cz = (box.minZ + box.maxZ) / 2;
        double head = Math.max(box.minY, box.maxY - FACE_INSET);
        double feet = Math.min(box.maxY, box.minY + FACE_INSET);
        double x0 = box.minX + CORNER_INSET;
        double x1 = box.maxX - CORNER_INSET;
        double z0 = box.minZ + CORNER_INSET;
        double z1 = box.maxZ - CORNER_INSET;
        return List.of(
                new Vec3(cx, eyeY, cz),
                new Vec3(cx, head, cz),
                new Vec3(cx, (box.minY + box.maxY) / 2, cz),
                new Vec3(cx, feet, cz),
                new Vec3(x0, head, z0),
                new Vec3(x1, head, z0),
                new Vec3(x0, head, z1),
                new Vec3(x1, head, z1));
    }

    /** Whether any sample is reached by a clear ray; {@code rayClear} answers for one target point. */
    public static boolean anyVisible(List<Vec3> samples, Predicate<Vec3> rayClear) {
        for (Vec3 sample : samples) {
            if (rayClear.test(sample)) {
                return true;
            }
        }
        return false;
    }
}
