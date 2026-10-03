/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.api;

import sbs.modid.client.core.config.ConfigManager;

import java.net.http.HttpRequest;

/**
 * Shared helper for Hypixel HTTP requests: applies the configured developer API key (config →
 * {@code api.hypixelApiKey}) as the official {@code API-Key} header. No header is sent when the
 * key is blank – all endpoints used by SBS also work keyless, the key only raises rate limits.
 */
public final class HypixelApi {

    private HypixelApi() {
    }

    /** Adds the {@code API-Key} header when a key is configured; always adds the User-Agent. */
// ============================================================================
// [MODERATOR AUDIT / NETWORK DISCLOSURE]
// KEYWORDS: NETWORK_OUTBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
// ENDPOINT: request builder - applies to every https://api.hypixel.net/v2 route
// METHOD: header mutation, no traffic of its own
// PURPOSE: Identify the mod to Hypixel, and raise the rate limit when the user has entered
//   their own developer key.
// DATA SENT: User-Agent: SkyblockSimplifiedSBS on every request. API-Key: <key> only when
//   the user has put a key in the config themselves; no header at all when it is blank.
// DATA RECEIVED: Nothing.
// SAFETY DECLARATION: The API key is the user's own Hypixel developer key, typed in by them
//   and sent only to Hypixel, who issued it. Every endpoint this mod uses also works with no
//   key at all - the key only raises rate limits. No Mojang credentials, no session id, no
//   OS telemetry.
// ============================================================================
    public static HttpRequest.Builder withHeaders(HttpRequest.Builder builder) {
        builder.header("User-Agent", "SkyblockSimplifiedSBS");
        String key = ConfigManager.getInstance().get().api.hypixelApiKey;
        if (key != null && !key.isBlank()) {
            builder.header("API-Key", key.trim());
        }
        return builder;
    }
}
