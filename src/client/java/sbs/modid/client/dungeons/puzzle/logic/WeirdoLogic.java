/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.puzzle.logic;

import java.util.List;

/**
 * The Three Weirdos deduction, as a pure function: three typed claims in, the one chest they can
 * only mean, or "not determined", out.
 *
 * <p>Kept apart from {@link ThreeWeirdosSolver} because it is the half of the puzzle that can be
 * settled without ever entering a dungeon. Text parsing depends on wording nobody has transcribed
 * yet; <i>this</i> depends on nothing but the rules, so it is written and unit-tested now and the
 * parser is filled in from real lines later against a solver already known to be right.
 *
 * <p><b>The rule it applies: exactly one of the three is telling the truth.</b> That is the
 * conventional statement of the puzzle and it is <b>unverified</b> here - {@code WIKI}, in this
 * repository's terms. It matters because it is the difference between a unique answer and several,
 * so if the real rule turns out to be "at least one" or "the correct chest's owner never lies", this
 * function is where that is fixed. Everything it can do wrong it does by returning
 * {@link #UNDETERMINED}, never by picking one of several candidates.
 */
public final class WeirdoLogic {

    /** No single chest satisfies the rule - the caller shows nothing. */
    public static final int UNDETERMINED = -1;

    /** What one weirdo asserted. */
    public enum Kind {
        /** "The reward is in my chest." */
        MINE,
        /** "The reward is not in my chest." */
        NOT_MINE,
        /** "The reward is in <target>'s chest." */
        OTHER,
        /** "The reward is not in <target>'s chest." */
        NOT_OTHER
    }

    /**
     * One weirdo's claim.
     *
     * @param speaker index 0-2 of who said it
     * @param kind    what sort of claim it is
     * @param target  the chest index the claim is about, for {@link Kind#OTHER} / {@link Kind#NOT_OTHER};
     *                ignored otherwise
     */
    public record Claim(int speaker, Kind kind, int target) {

        /** Whether this claim is true when the reward is in {@code chest}. */
        boolean holdsWhenRewardIn(int chest) {
            return switch (kind) {
                case MINE -> chest == speaker;
                case NOT_MINE -> chest != speaker;
                case OTHER -> chest == target;
                case NOT_OTHER -> chest != target;
            };
        }
    }

    private WeirdoLogic() {
    }

    /**
     * The chest the claims uniquely identify, or {@link #UNDETERMINED}.
     *
     * <p>Brute force over three possibilities, because three is three - a cleverer search would be
     * harder to check and no faster. A chest is a candidate when exactly one claim is true with the
     * reward in it; the answer is returned only when exactly one candidate survives.
     *
     * <p>Returns {@link #UNDETERMINED} for anything less than a complete, well-formed set of three
     * claims. A partially-read room is not a solvable room.
     */
    public static int solve(List<Claim> claims) {
        if (claims == null || claims.size() != 3) {
            return UNDETERMINED;
        }
        boolean[] spoken = new boolean[3];
        for (Claim claim : claims) {
            if (claim == null || claim.speaker() < 0 || claim.speaker() > 2
                    || spoken[claim.speaker()]) {
                return UNDETERMINED;   // a missing, malformed or duplicated speaker
            }
            if ((claim.kind() == Kind.OTHER || claim.kind() == Kind.NOT_OTHER)
                    && (claim.target() < 0 || claim.target() > 2)) {
                return UNDETERMINED;   // a claim about a chest that does not exist
            }
            spoken[claim.speaker()] = true;
        }

        int candidate = UNDETERMINED;
        for (int chest = 0; chest < 3; chest++) {
            int truths = 0;
            for (Claim claim : claims) {
                if (claim.holdsWhenRewardIn(chest)) {
                    truths++;
                }
            }
            if (truths != 1) {
                continue;
            }
            if (candidate != UNDETERMINED) {
                return UNDETERMINED;   // two chests fit the rule - the room is ambiguous, say nothing
            }
            candidate = chest;
        }
        return candidate;
    }
}
