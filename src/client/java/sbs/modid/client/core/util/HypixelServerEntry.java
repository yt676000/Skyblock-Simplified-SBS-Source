/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.util;

import net.minecraft.client.multiplayer.ServerData;
import sbs.modid.client.core.config.ConfigManager;

import java.util.List;
import java.util.Locale;

/**
 * Keeps Hypixel at the top of the multiplayer server list.
 *
 * <p>The list is re-read from {@code servers.dat} every time the multiplayer screen opens, so
 * {@link #pin} runs on every open and the entry is back at the top however the list was left.
 *
 * <p>An entry the player already has is <b>moved</b>, never replaced: their own name, and whichever
 * {@code hypixel.net} address they picked, are what stays in the list. Only when there is no Hypixel
 * entry at all is one created.
 */
public final class HypixelServerEntry {

    /** Name given to a freshly created entry. An existing entry keeps whatever the player called it. */
    public static final String NAME = "Hypixel";
    /**
     * The address SBS connects to and gives a freshly created list entry. One constant for the whole
     * mod, so the main-menu button and the server list can never drift apart. An entry the player
     * already has keeps its own address - this is only ever used when there is nothing to keep.
     */
    public static final String ADDRESS = "mc.hypixel.net";
    private static final String DOMAIN = "hypixel.net";

    private HypixelServerEntry() {
    }

    /**
     * Moves the first Hypixel entry to the front of {@code servers}, or inserts one when there is
     * none. Does nothing when the feature is off or Hypixel is already first, which makes repeated
     * calls free and keeps the list stable.
     */
    public static void pin(List<ServerData> servers) {
        if (!ConfigManager.getInstance().get().convenience.pinHypixelServer) {
            return;
        }
        int existing = -1;
        for (int i = 0; i < servers.size(); i++) {
            if (isHypixel(servers.get(i).ip)) {
                existing = i;
                break;
            }
        }
        if (existing == 0) {
            return;
        }
        if (existing > 0) {
            servers.add(0, servers.remove(existing));
        } else {
            servers.add(0, new ServerData(NAME, ADDRESS, ServerData.Type.OTHER));
        }
    }

    /** Connects straight to Hypixel, the way the multiplayer list would on a double-click. */
    public static void connect(net.minecraft.client.gui.screens.Screen parent) {
        net.minecraft.client.gui.screens.ConnectScreen.startConnecting(
                parent, net.minecraft.client.Minecraft.getInstance(),
                net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(ADDRESS),
                new ServerData(NAME, ADDRESS, ServerData.Type.OTHER), false, null);
    }

    /**
     * Whether an address belongs to Hypixel – {@code mc.}, {@code mvp.} or the bare domain, port and
     * all. Matching on the domain rather than one exact address is what lets a player keep their own
     * entry; requiring the leading dot on a subdomain is what stops {@code nothypixel.net} counting.
     */
    public static boolean isHypixel(String address) {
        if (address == null) {
            return false;
        }
        String host = address.trim().toLowerCase(Locale.ROOT);
        int port = host.indexOf(':');
        if (port >= 0) {
            host = host.substring(0, port);
        }
        return host.equals(DOMAIN) || host.endsWith("." + DOMAIN);
    }
}
