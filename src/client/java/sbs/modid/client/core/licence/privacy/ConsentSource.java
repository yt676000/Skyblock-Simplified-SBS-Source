/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.licence.privacy;

import java.util.Locale;

/**
 * How a stored answer came about. Recorded alongside every {@link ConsentState} and mirrored to the
 * backend's audit log, because "the user consented" is a claim that has to be evidenced, and the
 * evidence has to say <i>how</i> - a per-row tick and a bulk "accept all" are not the same quality
 * of agreement, and neither is a value that a migration wrote.
 *
 * <p>Ids are stable strings for the same reason {@link ConsentScope#id()} is.
 */
public enum ConsentSource {

    /** The user ticked this one row themselves. The strongest form. */
    USER("user"),

    /** The user pressed "Accept all". Still explicit, but not per-purpose. */
    ACCEPT_ALL("accept_all"),

    /** The user pressed "Decline all". */
    DECLINE_ALL("decline_all"),

    /**
     * Written by the update path, never as a grant. Existing users arrive here with everything off:
     * a consent regime that started after the data collection did cannot infer agreement from the
     * fact that someone was already using the feature.
     */
    MIGRATION("migration"),

    /** The absence of an answer - what every scope reads as until the user says otherwise. */
    DEFAULT("default");

    private final String id;

    ConsentSource(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /** Resolves a persisted id, falling back to {@link #DEFAULT} for anything unrecognised. */
    public static ConsentSource byId(String id) {
        if (id != null && !id.isBlank()) {
            String normalised = id.trim().toLowerCase(Locale.ROOT);
            for (ConsentSource source : values()) {
                if (source.id.equals(normalised)) {
                    return source;
                }
            }
        }
        return DEFAULT;
    }
}
