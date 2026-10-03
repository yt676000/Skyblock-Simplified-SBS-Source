/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.player;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.entity.Entity;

/**
 * Whether a player-shaped entity is a real player or one of Hypixel's NPCs - the one test for it.
 *
 * <p>SkyBlock's NPCs are ordinary player-model entities, so nothing on the entity separates them.
 * What does: <b>a real player has an entry in the client's player list</b> (the list the tab is
 * built from); an NPC is pushed as a world entity alone. Known gap: a server may put an NPC in the
 * list for a moment so its skin loads, and a player's entity can arrive a tick before its entry - each
 * costs one tick of the wrong answer.
 *
 * <p><b>The UUID version is deliberately not a second signal.</b> It was checked against the
 * instance logs on 2026-09-25: the server-made profiles there (the ones Minecraft flags for a texture
 * signature Mojang did not make) include version-2 and version-3 UUIDs - and 275 version-4 ones, 230
 * of them with one synthetic prefix. Hypixel fabricates version-4 UUIDs, so "version 4 means a real
 * account" would let fabricated entities through. Details in {@code docs/features/hide-nearby-players.md}.
 */
public final class RealPlayers {

    private RealPlayers() {
    }

    /** The decision itself, kept pure so the table is unit-tested. */
    public static boolean decide(boolean playerShaped, boolean inPlayerList) {
        return playerShaped && inPlayerList;
    }

    /** Whether {@code entity} (assumed player-shaped by the caller) is a real player right now. */
    public static boolean isRealPlayer(Entity entity) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        return entity != null && decide(true, connection != null
                && connection.getPlayerInfo(entity.getUUID()) != null);
    }
}
