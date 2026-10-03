/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import sbs.modid.client.combat.diana.model.HubWarp;
import sbs.modid.client.core.config.ConfigManager;

/**
 * Which Hub warp lands nearest a burrow, and whether it is worth taking.
 *
 * <h2>Arithmetic, shown - never sent</h2>
 *
 * <p>This class returns a warp's name. Nothing here sends a command, and that is a decision rather
 * than an omission. Working out which warp is nearest is a distance comparison over seven
 * coordinates and is exactly the sort of thing a mod should do for a player. <i>Taking</i> the warp
 * is the mod acting - and unlike the conveniences this project does allow, picking the optimal warp
 * every time is a rate improvement, which fails the first condition those conveniences have to meet.
 *
 * <p>The mod already has a mechanism for "one key press, one command" that the player configures
 * themselves. Naming the warp here and letting their own bind send it is the arrangement that gives
 * them the calculation without the client playing for them.
 *
 * <h2>Nearest is not the same as best</h2>
 *
 * <p>Two adjustments, both about the shape of the Hub rather than about the numbers:
 *
 * <ul>
 *   <li>the two high warps are compared without their Y, because fifty blocks of drop is free and
 *       counting it makes them look further away than they are;</li>
 *   <li>a warp that is slow to walk out of loses to one slightly further out in the open, because
 *       the distance that matters is time and not blocks.</li>
 * </ul>
 *
 * <h2>And sometimes the answer is "don't"</h2>
 *
 * <p>A warp that saves less than a short walk is not worth the loading screen. When nothing beats
 * walking, this says so by returning {@code null}, and the marker simply carries no suggestion -
 * which is the honest output, and quieter than a suggestion the player has to learn to ignore.
 */
public final class WarpSuggestion {

    /** How much closer a warp must land than the player already is before it is worth taking. */
    private static final double WORTH_IT_MARGIN = 40.0;

    /** How much further an easy-exit warp may be and still beat an awkward one. */
    private static final double AWKWARD_PENALTY = 60.0;

    private WarpSuggestion() {
    }

    /**
     * The warp to take to reach {@code target}, or {@code null} when walking is as good.
     *
     * @param target the burrow or guess being walked to
     */
    public static HubWarp bestFor(BlockPos target) {
        if (!ConfigManager.getInstance().get().diana.suggestWarp || target == null) {
            return null;
        }
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return null;
        }
        Vec3 destination = Vec3.atCenterOf(target);
        double walking = player.position().distanceTo(destination);

        HubWarp best = null;
        double bestCost = Double.MAX_VALUE;
        for (HubWarp warp : HubWarp.values()) {
            double cost = cost(warp, destination);
            if (cost < bestCost) {
                bestCost = cost;
                best = warp;
            }
        }
        if (best == null || bestCost + WORTH_IT_MARGIN >= walking) {
            return null;
        }
        return best;
    }

    /**
     * How far the player would still have to travel after taking this warp, adjusted.
     *
     * <p>"Adjusted" is the whole content of this method: the raw distance is a poor proxy for time,
     * and the two corrections are what make it a usable one.
     */
    private static double cost(HubWarp warp, Vec3 destination) {
        Vec3 arrival = warp.pos();
        double distance = warp.elevated()
                ? Math.hypot(arrival.x - destination.x, arrival.z - destination.z)
                : arrival.distanceTo(destination);
        return warp.awkwardExit() ? distance + AWKWARD_PENALTY : distance;
    }

    /** "Castle" - what goes on the marker, or {@code ""} when walking is the answer. */
    public static String labelFor(BlockPos target) {
        HubWarp warp = bestFor(target);
        return warp == null ? "" : warp.displayName();
    }
}
