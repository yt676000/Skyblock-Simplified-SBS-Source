/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;

/**
 * The singleplayer-only rule, as one predicate.
 *
 * <p>Build Tools changes the world only against the integrated server of a singleplayer world. On a
 * server - Hypixel above all - it reads the blocks the client already has and draws; it never places,
 * breaks or clicks a block, because that would be the mod playing the game (see "The mod never plays
 * the game for the player" in AGENTS.md).
 *
 * <p>The rule is checked in three places on purpose: the command refuses first
 * ({@code BuildCommand.refusal}), the edit engine refuses again before it queues anything, and every
 * slice re-checks {@link #server()} on the server thread before a single {@code setBlock}. A later
 * code path that forgets the first check still cannot reach a server world.
 */
public final class BuildGate {

    private BuildGate() {
    }

    /** True while an integrated (singleplayer or LAN-host) server runs in this client. */
    public static boolean singleplayer() {
        return Minecraft.getInstance().getSingleplayerServer() != null;
    }

    /** The integrated server, or {@code null} when there is none - never a remote one. */
    public static IntegratedServer server() {
        return Minecraft.getInstance().getSingleplayerServer();
    }
}
