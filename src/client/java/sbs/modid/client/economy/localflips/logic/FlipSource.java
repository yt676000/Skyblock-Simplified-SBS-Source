/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.localflips.logic;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.regex.Pattern;

/**
 * Where the Local Flip Finder and Lowball get their data: the flip program on this PC / the LAN
 * ({@code local}, the address fields as before), or a friend's PC over Radmin VPN ({@code vpn}).
 *
 * <p><b>VPN mode.</b> The player types the host's Radmin IP themselves (no default). Only an IPv4
 * literal is accepted, and only one that is local/LAN or inside Radmin VPN's own 26.0.0.0/8 range:
 * the token travels in a plain-http header, so an address on the open internet is refused rather
 * than trusted to the player's typing. Radmin encrypts the tunnel itself.
 */
public final class FlipSource {

    public static final String LOCAL = "local";
    public static final String VPN = "vpn";
    public static final String NEED_IP = "Enter the host's Radmin IP first (/sbs flips source vpn <ip>)";

    private static final Pattern IPV4 = Pattern.compile(
            "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");

    private FlipSource() {
    }

    public static SBSConfig.FlipSourceSettings cfg() {
        return ConfigManager.getInstance().get().dev.flipSource;
    }

    public static boolean isVpn() {
        return VPN.equalsIgnoreCase(cfg().mode);
    }

    /** {@code [LOCAL]} or {@code [VPN]}, for chat lines and the Lowball tooltip. */
    public static String tag() {
        return isVpn() ? "[VPN]" : "[LOCAL]";
    }

    /** True for a dotted IPv4 literal that VPN mode may use (format and range). */
    public static boolean validVpnHost(String host) {
        if (host == null || !IPV4.matcher(host.trim()).matches()) {
            return false;
        }
        try {
            return allowed(InetAddress.getByName(host.trim()), true);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * The base address to use: in VPN mode {@code http://host:port} (null when no valid IP is set),
     * otherwise the feature's own address field as before.
     */
    public static URI base(String localUrl) {
        if (!isVpn()) {
            return LocalAddress.base(localUrl);
        }
        SBSConfig.FlipSourceSettings c = cfg();
        String host = c.vpnHost == null ? "" : c.vpnHost.trim();
        if (!validVpnHost(host)) {
            return null;
        }
        int port = c.vpnPort > 0 && c.vpnPort <= 65_535 ? c.vpnPort : 3020;
        return URI.create("http://" + host + ":" + port);
    }

    /** True when VPN mode is on but no usable IP is entered. */
    public static boolean missingVpnHost() {
        return isVpn() && base(null) == null;
    }

    /** Settings fingerprint, so a change restarts the stream. */
    public static String key() {
        SBSConfig.FlipSourceSettings c = cfg();
        return c.mode + '|' + c.vpnHost + '|' + c.vpnPort;
    }

    /** Local/LAN always; in VPN mode also Radmin VPN's 26.0.0.0/8. */
    public static boolean allowed(InetAddress address, boolean vpn) {
        if (LocalAddress.isLocal(address)) {
            return true;
        }
        return vpn && address instanceof Inet4Address && (address.getAddress()[0] & 0xFF) == 26;
    }
}
