/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.notes.model;

/**
 * What you think of a noted player. Three values, so every screen that sets it shows all three at
 * once (a segmented switch), never a cycler.
 *
 * <p>The enum <b>name</b> is what the notes file stores, so it is an id: never rename a constant.
 * The label and colour are presentation and may change freely.
 */
public enum NoteTag {

    TRUSTED("Trusted", 0xFF55FF55),
    NEUTRAL("Neutral", 0xFFAAAAAA),
    AVOID("Avoid", 0xFFFF5555);

    private final String label;
    private final int color;

    NoteTag(String label, int color) {
        this.label = label;
        this.color = color;
    }

    public String label() {
        return label;
    }

    /** ARGB. Never the only signal: every place that colours by tag also writes the label. */
    public int color() {
        return color;
    }
}
