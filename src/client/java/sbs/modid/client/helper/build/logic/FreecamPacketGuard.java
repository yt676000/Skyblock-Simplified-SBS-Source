/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.helper.build.model.FreecamPacketRules;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Freecam's network rule, enforced: while either freecam is on, every packet the client's own game
 * connection sends passes {@link #filter}, which lets through only what a player standing still would
 * send ({@link FreecamPacketRules}). Movement packets are rebuilt from the player entity, never from
 * the camera, so even a bug elsewhere cannot leak the camera position. Everything else is dropped and
 * logged once per type.
 *
 * <p>This is the safety net, not the mechanism: build freecam's clicks are handled client-side and
 * never make a packet, and cinematic's do nothing. A "blocked" line in the log therefore means
 * something slipped past the input locks and is worth a look.
 *
 * <p>Only the connection of {@code Minecraft.getConnection()} is filtered - in singleplayer the
 * integrated server's own connections pass the same {@code Connection.send} and are left alone.
 */
public final class FreecamPacketGuard {

    private static final Map<String, Integer> SENT = new TreeMap<>();
    private static final Map<String, Integer> BLOCKED = new TreeMap<>();
    private static final Set<String> LOGGED = new HashSet<>();

    private FreecamPacketGuard() {
    }

    /** Freecam started: the counts start over. */
    static void begin() {
        synchronized (SENT) {
            SENT.clear();
            BLOCKED.clear();
            LOGGED.clear();
        }
    }


    /**
     * The packet to send instead of {@code packet}: itself, a rebuilt movement packet, or {@code null}
     * to drop it. Called for every outgoing packet on every connection; returns at once while freecam
     * is off or the connection is not the player's.
     */
    public static Packet<?> filter(Connection connection, Packet<?> packet) {
        if (!Freecam.active()) {
            return packet;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener listener = minecraft.getConnection();
        LocalPlayer player = minecraft.player;
        if (listener == null || player == null || listener.getConnection() != connection) {
            return packet;
        }
        String name = FreecamPacketRules.name(packet.getClass().getName());
        boolean screenOpen = sbs.modid.client.core.api.ScreenAccess.current() != null;
        Packet<?> out = switch (FreecamPacketRules.verdict(name, screenOpen)) {
            case ALLOW -> packet;
            case REBUILD -> rebuild(packet, player);
            case BLOCK -> null;
        };
        synchronized (SENT) {
            (out == null ? BLOCKED : SENT).merge(name, 1, Integer::sum);
            if (out == null && LOGGED.add(name)) {
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Freecam] blocked {} while in freecam", name);
            }
        }
        return out;
    }

    /** The same kind of movement packet, with the player entity's real values. */
    private static Packet<?> rebuild(Packet<?> packet, LocalPlayer player) {
        boolean ground = player.onGround();
        boolean wall = player.horizontalCollision;
        return switch (packet) {
            case ServerboundMovePlayerPacket.PosRot ignored -> new ServerboundMovePlayerPacket.PosRot(
                    player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot(), ground, wall);
            case ServerboundMovePlayerPacket.Pos ignored -> new ServerboundMovePlayerPacket.Pos(
                    player.getX(), player.getY(), player.getZ(), ground, wall);
            case ServerboundMovePlayerPacket.Rot ignored -> new ServerboundMovePlayerPacket.Rot(
                    player.getYRot(), player.getXRot(), ground, wall);
            case ServerboundMovePlayerPacket.StatusOnly ignored -> new ServerboundMovePlayerPacket.StatusOnly(ground, wall);
            default -> null;
        };
    }

    /** {@code /sbs freecam packets}: sent and blocked counts by type since freecam last started. */
    public static String report() {
        synchronized (SENT) {
            return "sent " + (SENT.isEmpty() ? "nothing" : SENT) + " | blocked "
                    + (BLOCKED.isEmpty() ? "nothing" : BLOCKED);
        }
    }
}
