/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.auctions.logic;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

import java.net.URI;

/**
 * Where the AH flip algorithm lives: the SBS server (default) or, for development only, the flip
 * program on this PC.
 *
 * <p>Both speak the same protocol with the same JSON; only the address, two paths and the token
 * differ. The server sits behind nginx at {@code https://skyblocksimplified.info/flips/...} and
 * wants the user's licence token as {@code X-SBS-Token}; the dev program answers at
 * {@code http://127.0.0.1:3020} with {@code /health} and {@code /lowball} at its root.
 *
 * <p><b>Choice.</b> "Local" counts only when the player picked it on the switch ({@code pinned}):
 * {@link SBSConfig.SourceChoice} defaults to {@code preferLocal = true}, which for every other
 * feature means "compute here". Here Local means a dev program most players never run, so an
 * untouched config must land on the server instead of on a dead localhost.
 */
public final class FlipServer {

    public static final URI SERVER = URI.create("https://skyblocksimplified.info");
    public static final URI LOCAL = URI.create("http://127.0.0.1:3020");

    private FlipServer() {
    }

    private static SBSConfig.SourceChoice choice() {
        return ConfigManager.getInstance().get().ahFlips.flipSource;
    }

    /** True only when the player explicitly picked Local. */
    public static boolean isLocal() {
        SBSConfig.SourceChoice c = choice();
        return c != null && c.preferLocal && c.pinned;
    }

    public static URI base() {
        return isLocal() ? LOCAL : SERVER;
    }

    public static String healthPath() {
        return isLocal() ? "/health" : "/flips/health";
    }

    public static String lowballPath() {
        return isLocal() ? "/lowball" : "/flips/lowball";
    }

    /** The licence token; the server checks it (and its IP binding). Empty when none is set. */
    public static String token() {
        String token = sbs.modid.client.core.config.LicenceToken.getInstance().get();
        return token == null ? "" : token.trim();
    }

    /** Settings fingerprint: a change restarts the stream. */
    public static String key() {
        return base() + "|" + token().hashCode();
    }
}
