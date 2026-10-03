/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.kuudra.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import sbs.modid.client.combat.kuudra.model.KuudraPhase;
import sbs.modid.client.combat.kuudra.render.KuudraAlert;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

/**
 * The floor call-out for the final phase.
 *
 * <p><b>The arena floor tells you where the next slam lands, and it is behind you.</b> In the last
 * phase the tiles under everyone's feet change colour ahead of each attack - green while it is safe,
 * amber as it winds up, red on the beat - and the one place you are not looking during a damage
 * window is down. So the block under the player is read instead, and only the change is announced.
 *
 * <p><b>Read by colour name, not by block constant.</b> The floor is coloured terracotta and the
 * relevant part is which colour, so the registry name is matched rather than a list of block objects -
 * that survives a block being renamed or a shade being swapped, which a hard-coded constant does not.
 *
 * <p>Five blocks down, because the tile you are standing <i>on</i> can be a slab, a stair or a player
 * standing on another player's head, and the coloured floor is somewhere underneath all of that.
 */
public final class DangerZone {

    private static final DangerZone INSTANCE = new DangerZone();

    /** How far below the feet to look for the coloured floor. */
    private static final int PROBE_DEPTH = 5;

    /** Four checks a second: the floor changes on the attack's rhythm, not per frame. */
    private static final long CHECK_INTERVAL_MS = 250L;

    /** How the floor reads right now. */
    public enum Level {
        /** Nothing under us, or nothing coloured. */
        NONE,
        /** Green or lime: stay. */
        SAFE,
        /** Yellow or orange: it is winding up. */
        WARNING,
        /** Red: move now. */
        CRITICAL
    }

    private volatile Level level = Level.NONE;
    private long lastCheck;

    private DangerZone() {
    }

    public static DangerZone getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.KuudraSettings cfg() {
        return ConfigManager.getInstance().get().kuudra;
    }

    public Level level() {
        return level;
    }

    public void reset() {
        level = Level.NONE;
    }

    /** Called from the module's tick. Inert outside the final phase. */
    public void onClientTick() {
        SBSConfig.KuudraSettings cfg = cfg();
        KuudraTracker tracker = KuudraTracker.getInstance();
        if (!cfg.enabled || !cfg.dangerAlert || tracker.phase() != KuudraPhase.BOSS) {
            if (level != Level.NONE) {
                level = Level.NONE;
            }
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastCheck < CHECK_INTERVAL_MS) {
            return;
        }
        lastCheck = now;

        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel world = minecraft.level;
        if (player == null || world == null) {
            return;
        }
        Level found = probe(world, player.blockPosition());
        if (found == level) {
            return;
        }
        Level previous = level;
        level = found;
        // Only ever announced on the way up. Coming back down to safe is good news, and good news
        // does not need a title in the middle of a damage window.
        if (found.ordinal() > previous.ordinal() && found.ordinal() >= Level.WARNING.ordinal()) {
            boolean critical = found == Level.CRITICAL;
            KuudraAlert.getInstance().flash(critical ? "JUMP!" : "DANGER",
                    critical ? 0xFFFF4040 : 0xFFFFE000, cfg.dangerSound, critical ? 2.0f : 1.2f);
        }
    }

    /** The strongest colour found in the column below {@code from}. */
    private static Level probe(ClientLevel world, BlockPos from) {
        for (int depth = 0; depth <= PROBE_DEPTH; depth++) {
            BlockPos at = from.below(depth);
            String name = BuiltInRegistries.BLOCK.getKey(world.getBlockState(at).getBlock()).getPath();
            if (!name.endsWith("_terracotta")) {
                continue;
            }
            if (name.startsWith("red_")) {
                return Level.CRITICAL;
            }
            if (name.startsWith("yellow_") || name.startsWith("orange_")) {
                return Level.WARNING;
            }
            if (name.startsWith("green_") || name.startsWith("lime_")) {
                return Level.SAFE;
            }
        }
        return Level.NONE;
    }
}
