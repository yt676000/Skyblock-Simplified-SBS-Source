/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.localflips.command;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.economy.localflips.logic.FlipSource;
import sbs.modid.client.economy.localflips.logic.LocalFlipFinder;
import sbs.modid.client.social.chat.logic.SBSChat;

import java.util.Locale;

/**
 * {@code /sbs flips source local | source vpn <ip> [port] | token <token> | test}. The finder notices
 * a changed source on its next tick and reconnects; the token is never echoed (the chat mixin also
 * keeps the token command out of the arrow-key history).
 */
public final class FlipSourceCommand {

    private static final String USAGE =
            "Usage: /sbs flips source local | source vpn <ip> [port] | token <token> | test";

    private FlipSourceCommand() {
    }

    public static void handle(String args) {
        String[] parts = args.isEmpty() ? new String[0] : args.split("\\s+");
        if (parts.length == 0) {
            SBSChat.send(USAGE);
            return;
        }
        SBSConfig.FlipSourceSettings source = FlipSource.cfg();
        switch (parts[0].toLowerCase(Locale.ROOT)) {
            case "source" -> {
                if (parts.length >= 2 && parts[1].equalsIgnoreCase("local")) {
                    source.mode = FlipSource.LOCAL;
                    save();
                    SBSChat.send("Flip source: local (this PC / LAN address from the settings).");
                } else if (parts.length >= 2 && parts[1].equalsIgnoreCase("vpn")) {
                    if (parts.length < 3) {
                        source.mode = FlipSource.VPN;
                        save();
                        SBSChat.send(FlipSource.validVpnHost(source.vpnHost)
                                ? "Flip source: VPN " + source.vpnHost + ":" + source.vpnPort + "."
                                : FlipSource.NEED_IP);
                        return;
                    }
                    if (!FlipSource.validVpnHost(parts[2])) {
                        SBSChat.send("Not a usable Radmin IP: IPv4 only, e.g. 26.12.34.56.");
                        return;
                    }
                    int port = source.vpnPort;
                    if (parts.length >= 4) {
                        try {
                            port = Integer.parseInt(parts[3]);
                        } catch (NumberFormatException e) {
                            port = -1;
                        }
                        if (port < 1 || port > 65_535) {
                            SBSChat.send("Port must be 1-65535.");
                            return;
                        }
                    }
                    source.mode = FlipSource.VPN;
                    source.vpnHost = parts[2];
                    source.vpnPort = port;
                    save();
                    SBSChat.send("Flip source: VPN " + source.vpnHost + ":" + port + ".");
                } else {
                    SBSChat.send(USAGE);
                }
            }
            case "token" -> {
                if (parts.length < 2) {
                    SBSChat.send(USAGE);
                    return;
                }
                LocalFlipFinder.cfg().token = parts[1];
                save();
                SBSChat.send("Token saved");
            }
            case "test" -> LocalFlipFinder.testConnection();
            default -> SBSChat.send(USAGE);
        }
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }
}
