/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.loadouts;

import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.function.Function;

/**
 * Turns a stack into its persisted JSON without ever losing JSON it already had.
 *
 * <p><b>Why this exists.</b> An {@code ItemStack} carries registry references (its enchantments)
 * that belong to the world it was read in. After a server hop the level has a new registry set,
 * and encoding an older stack with it fails with "... is not valid in current registry set". The
 * cache writer used to turn that into a {@code null} on disk, which is how loadout 1 lost its
 * loadout item - and with it the widget card - on the next restart.
 *
 * <p>So an encode tries, in order: the current world's ops, the previous world's ops (the stack's
 * references are valid there), and finally the last JSON this stack was read from or written as.
 * Only when all three come up empty is it a real failure.
 *
 * <p>Keyed by identity on purpose: {@code ItemStack} has no {@code equals}, a replaced stack is a
 * new key, and a weak key lets a dropped stack's JSON go with it.
 *
 * @param <S> the stack type ({@code ItemStack} in the game, anything in a test)
 * @param <J> the JSON type
 */
final class StackJsonMemory<S, J> {

    /** What one encode produced, and how. */
    enum Via { CURRENT, PREVIOUS, REMEMBERED, FAILED }

    record Result<J>(J json, Via via) {
    }

    private final Map<S, J> lastGood = new WeakHashMap<>();

    /** Records the JSON a stack was decoded from, so a later failed encode can fall back to it. */
    synchronized void remember(S stack, J json) {
        if (stack != null && json != null) {
            lastGood.put(stack, json);
        }
    }

    /**
     * Encodes {@code stack}.
     *
     * @param current  the current world's encoder
     * @param previous the previous world's encoder, or {@code null} when there was none
     */
    synchronized Result<J> encode(S stack, Function<S, Optional<J>> current,
                                  Function<S, Optional<J>> previous) {
        Optional<J> json = current.apply(stack);
        if (json.isPresent()) {
            lastGood.put(stack, json.get());
            return new Result<>(json.get(), Via.CURRENT);
        }
        if (previous != null) {
            json = previous.apply(stack);
            if (json.isPresent()) {
                lastGood.put(stack, json.get());
                return new Result<>(json.get(), Via.PREVIOUS);
            }
        }
        J remembered = lastGood.get(stack);
        return remembered != null
                ? new Result<>(remembered, Via.REMEMBERED)
                : new Result<>(null, Via.FAILED);
    }
}
