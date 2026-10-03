/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.pathfinding;

import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

/**
 * What the player can currently <b>do</b>, which is what the pathfinder has to route within: are
 * they flying, and how high can they jump right now.
 *
 * <p><b>Jump height is measured, not tabled.</b> A hardcoded "you can step up one block" is wrong
 * the moment a jump-boost potion is active – SkyBlock's goes several blocks high, and a route that
 * refuses to use a ledge the player can trivially hop is just wrong. Rather than keep a table of
 * potion levels, this simulates vanilla's own jump arc from the player's live jump velocity
 * ({@code JUMP_STRENGTH} attribute + {@link Player#getJumpBoostPower()}), so <i>any</i> amplifier –
 * including ones Hypixel invents later – produces the right height with no code change.
 *
 * <p>Sanity check on the simulation: with no effect it yields ~1.25 blocks, which is exactly
 * vanilla's documented jump height.
 *
 * <p><b>Caveat.</b> This can only see effects the server actually gives the client. If a jump boost
 * is applied purely server-side, the effect is invisible here – hence the manual height override in
 * the dev settings.
 */
public final class PlayerMobility {

    /** Vanilla vertical physics: gravity per tick and the drag applied after it. */
    private static final double GRAVITY = 0.08;
    private static final double DRAG = 0.98;

    /** Safety stop for the arc integration (a jump never lasts anywhere near this long). */
    private static final int MAX_TICKS = 200;

    /** Never let the search consider a step-up taller than this, however absurd the potion. */
    private static final int MAX_STEP_UP_CAP = 32;

    private PlayerMobility() {
    }

    /**
     * The movement rules to search with.
     *
     * <p>Being a record is load-bearing: {@link PathfindingManager} throws the cached route away the
     * moment this stops being equal, so folding the teleports in here is what makes picking up or
     * putting away an Aspect re-route on its own, with no separate watcher.
     *
     * @param mode         {@link PathMode#WALK} or {@link PathMode#FLY} – never {@code AUTO}
     * @param maxStepUp    how many blocks a single move may climb (1 without a jump boost)
     * @param transmission the teleports available right now – see {@link Transmission}
     */
    public record Mobility(PathMode mode, int maxStepUp, Transmission transmission) {
    }

    /** Resolves the player's current mobility, honouring the dev-mode overrides. */
    public static Mobility of(Player player) {
        SBSConfig.PathfindingSettings cfg = ConfigManager.getInstance().get().pathfinding;
        PathMode mode = cfg.pathMode == null ? PathMode.AUTO : cfg.pathMode;
        if (mode == PathMode.AUTO) {
            mode = player.getAbilities().flying ? PathMode.FLY : PathMode.WALK;
        }
        return new Mobility(mode, maxStepUp(player, cfg), Transmission.of(player));
    }

    /** The climb height for one move: the override when set, else the measured jump height. */
    private static int maxStepUp(Player player, SBSConfig.PathfindingSettings cfg) {
        if (cfg.jumpHeightOverride > 0) {
            return Math.min(MAX_STEP_UP_CAP, cfg.jumpHeightOverride);
        }
        int height = (int) Math.floor(jumpHeight(player));
        return Math.max(1, Math.min(MAX_STEP_UP_CAP, height));
    }

    /** The player's maximum jump height in blocks, from their live jump velocity. */
    public static double jumpHeight(Player player) {
        double velocity = player.getAttributeValue(Attributes.JUMP_STRENGTH)
                + player.getJumpBoostPower();
        return apexOf(velocity);
    }

    /**
     * Integrates vanilla's jump arc and returns its apex.
     *
     * <p>Per tick the entity moves by its velocity, then the velocity becomes
     * {@code (v - gravity) * drag}. Iterating that until the velocity turns negative gives the exact
     * height the game itself would produce.
     */
    private static double apexOf(double velocity) {
        double y = 0;
        double v = velocity;
        double apex = 0;
        for (int tick = 0; tick < MAX_TICKS && v > 0; tick++) {
            y += v;
            v = (v - GRAVITY) * DRAG;
            apex = Math.max(apex, y);
        }
        return apex;
    }
}
