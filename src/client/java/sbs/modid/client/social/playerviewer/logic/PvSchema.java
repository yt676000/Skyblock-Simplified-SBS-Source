/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.playerviewer.logic;

import com.google.gson.JsonObject;
import sbs.modid.SkyblockSimplifiedSBS;

/**
 * Schema-version handling for {@code /v1/playerviewer}. The server sends
 * {@code X-SBS-Schema-Version}; version 1 is the raw Hypixel {@code /skyblock/profile} body.
 *
 * <ul>
 *   <li>missing, unparsable or unknown (newer) version -> the current parser is tried anyway;</li>
 *   <li>a known older version would get an adapter here (there is none yet);</li>
 *   <li>any parse or validation failure -> a minimal view carrying {@link #UNSUPPORTED}, never an
 *       exception and never a partial view.</li>
 * </ul>
 */
public final class PvSchema {

    public static final int CURRENT = 1;
    public static final String UNSUPPORTED = "Data format not supported, please update SBS";

    private PvSchema() {
    }

    /** The result of one load: a view the pages can always render, plus an optional notice. */
    public record Outcome(JsonObject view, String notice) {
        public boolean ok() {
            return notice == null;
        }
    }

    /** The header's version, or -1 when missing or not a small positive integer. */
    public static int version(String header) {
        if (header == null || header.isEmpty() || header.length() > 4) {
            return -1;
        }
        try {
            int v = Integer.parseInt(header.trim());
            return v > 0 ? v : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static Outcome load(String versionHeader, String body, String uuid, PvResources resources,
                               PvCompute.Decoder decoder, long nowMillis) {
        int version = version(versionHeader);
        try {
            // Every version up to and including CURRENT shares the v1 layout; newer or unknown
            // versions are tried with the current parser and fail closed if they do not fit.
            PvModel.Profile profile = PvParser.parseProfile(body, uuid);
            return new Outcome(PvCompute.view(profile, resources, decoder, nowMillis), null);
        } catch (PvInvalidDataException e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][PV] Rejected profile response (schema {}): {}",
                    version, e.getMessage());
            return minimal();
        } catch (RuntimeException e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][PV] Profile computation failed (schema {}): {}",
                    version, e.getClass().getSimpleName());
            return minimal();
        }
    }

    public static Outcome minimal() {
        JsonObject view = new JsonObject();
        view.addProperty("notice", UNSUPPORTED);
        return new Outcome(view, UNSUPPORTED);
    }
}
