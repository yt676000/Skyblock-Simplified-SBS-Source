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
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * Whether a player-shaped entity is a real player or one of Hypixel's NPCs - the one test for it.
 *
 * <p>SkyBlock's NPCs are ordinary player-model entities, so nothing on the entity separates them.
 * What does: <b>a real player has a <i>listed</i> entry in the client's player list</b>. An NPC with
 * a custom skin (Shadow Assassin, Lost Adventurer, ...) gets an entry too - that is how the server
 * ships the skin - but with the "listed" flag of the player-info packet off. {@code getPlayerInfo(uuid)}
 * answers for listed and unlisted entries alike, which is why a starred Shadow Assassin read as a
 * real player and lost its box in beta.12; the listed set is
 * {@link ClientPacketListener#getListedOnlinePlayers()}. A player's entity can arrive a tick before
 * its entry - one tick of the wrong answer, harmless for the ✯ box since real players never carry ✯.
 *
 * <p>{@link #hasPlayerListEntry} is the weaker "any entry, listed or not". The player-facing features
 * (hiding, notes, scale, hitboxes, Kuudra's player count) stay on it until the hub probe confirms that
 * Hypixel lists real players in every SkyBlock lobby - if it does not, the listed rule would stop
 * hiding them. See {@code docs/features/hide-nearby-players.md}.
 *
 * <p><b>The UUID version is deliberately not a second signal.</b> It was checked against the
 * instance logs on 2026-09-25: the server-made profiles there (the ones Minecraft flags for a texture
 * signature Mojang did not make) include version-2 and version-3 UUIDs - and 275 version-4 ones, 230
 * of them with one synthetic prefix. Hypixel fabricates version-4 UUIDs, so "version 4 means a real
 * account" would let fabricated entities through. Details in {@code docs/features/hide-nearby-players.md}.
 *
 * <p>The local player counts as real whether or not its own entry has arrived yet. Mob matchers use
 * {@link #isRealPlayerEntity} to exclude players while still accepting Hypixel's player-model mobs.
 */
public final class RealPlayers {

    private RealPlayers() {
    }

    /**
     * The decision itself, kept pure so the table is unit-tested: the local player, or a
     * player-shaped entity whose entry is listed. An unlisted entry is an NPC's skin, not a player.
     */
    public static boolean decide(boolean playerShaped, boolean localPlayer, boolean inPlayerList, boolean listed) {
        return localPlayer || (playerShaped && inPlayerList && listed);
    }

    /** Whether {@code entity} (assumed player-shaped by the caller) is a real player right now. */
    public static boolean isRealPlayer(Entity entity) {
        if (entity == null) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener connection = minecraft.getConnection();
        PlayerInfo info = connection == null ? null : connection.getPlayerInfo(entity.getUUID());
        return decide(true, entity == minecraft.player, info != null,
                info != null && connection.getListedOnlinePlayers().contains(info));
    }

    /**
     * Whether {@code entity} is the local player or has any player-list entry, listed or not - true
     * for skinned NPCs as well. For the player-facing features until the hub probe settles whether
     * real players are always listed; mob matchers use {@link #isRealPlayerEntity}.
     */
    public static boolean hasPlayerListEntry(Entity entity) {
        if (entity == null) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener connection = minecraft.getConnection();
        return entity == minecraft.player
                || (connection != null && connection.getPlayerInfo(entity.getUUID()) != null);
    }

    /** The probes' view of a player-shaped body: {@code inList=.. listed=.. uuidVersion=..}. */
    public static String describe(Entity entity) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        PlayerInfo info = connection == null ? null : connection.getPlayerInfo(entity.getUUID());
        boolean listed = info != null && connection.getListedOnlinePlayers().contains(info);
        return "inList=" + (info != null) + " listed=" + listed + " uuidVersion=" + entity.getUUID().version();
    }

    /**
     * Whether {@code entity} is a real player, asked of any entity - false for everything that is
     * not a {@link Player}, so a mob filter can call it on every candidate.
     */
    public static boolean isRealPlayerEntity(Entity entity) {
        return entity instanceof Player && isRealPlayer(entity);
    }
}
