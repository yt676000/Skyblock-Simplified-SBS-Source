/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.notes.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.player.Player;
import sbs.modid.client.social.notes.model.PlayerNoteBook;

import java.util.Set;
import java.util.TreeSet;

/**
 * Name to account-id lookups against what the client can already see: players standing in the
 * world and the entries of the tab list. Never a network call - the UUID of a player who is nowhere
 * near is simply unknown, and the note is matched by name until they are seen.
 *
 * <p>"Real player": a tab entry with a valid username and a UUID that can be an account's, or a
 * world entity that {@code core/player/RealPlayers} says is in the player list. The version check
 * alone is not enough - Hypixel fabricates version-4 UUIDs - but it still rejects the version-2 ones
 * and names like {@code !A-a}, without reading the display text (which matters in a dungeon, where
 * every teammate's tab row carries one and {@code TabWidgets.isPlayer} would reject them all).
 */
public final class PlayerLookup {

    private PlayerLookup() {
    }

    public static boolean isRealPlayer(PlayerInfo info) {
        return info != null && PlayerNoteBook.isValidName(info.getProfile().name())
                && PlayerNoteBook.accountUuid(info.getProfile().id()) != null;
    }

    /** The account id of {@code name} if the client can see them, otherwise {@code null}. */
    public static String uuidOf(String name) {
        if (!PlayerNoteBook.isValidName(name)) {
            return null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null) {
            for (Player player : minecraft.level.players()) {
                if (player.getGameProfile().name().equalsIgnoreCase(name) && sbs.modid.client.core.player.RealPlayers.isRealPlayer(player)) {
                    String uuid = PlayerNoteBook.accountUuid(player.getUUID());
                    if (uuid != null) {
                        return uuid;
                    }
                }
            }
        }
        ClientPacketListener connection = minecraft.getConnection();
        if (connection != null) {
            PlayerInfo info = connection.getPlayerInfo(name);
            if (isRealPlayer(info)) {
                return PlayerNoteBook.accountUuid(info.getProfile().id());
            }
        }
        return null;
    }

    /** Names the client can see right now (tab list + world), for tab completion. */
    public static Set<String> visibleNames() {
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener connection = minecraft.getConnection();
        if (connection != null) {
            for (PlayerInfo info : connection.getOnlinePlayers()) {
                if (isRealPlayer(info)) {
                    names.add(info.getProfile().name());
                }
            }
        }
        if (minecraft.level != null) {
            for (Player player : minecraft.level.players()) {
                String name = player.getGameProfile().name();
                if (PlayerNoteBook.isValidName(name) && sbs.modid.client.core.player.RealPlayers.isRealPlayer(player)
                        && PlayerNoteBook.accountUuid(player.getUUID()) != null) {
                    names.add(name);
                }
            }
        }
        names.remove(selfName());
        return names;
    }

    public static String selfName() {
        var player = Minecraft.getInstance().player;
        return player == null ? "" : player.getGameProfile().name();
    }
}
