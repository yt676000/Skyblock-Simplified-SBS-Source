/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.chat.model;

import java.util.List;

/** What Better Chat does with a line one {@link ChatSpamPreset} recognised. */
public enum SpamFilterMode {
    /** Shown as it arrives. */
    OFF("Off"),
    /** Not drawn at all. */
    HIDE("Hide"),
    /** Stacked into one line with a counter, even with Compact Duplicates off. */
    COMPACT("Compact");

    public static final List<String> LABELS = List.of(OFF.label, HIDE.label, COMPACT.label);

    public final String label;

    SpamFilterMode(String label) {
        this.label = label;
    }
}
