/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.mobhighlight.model;

/**
 * The palette a highlighted mob's box can use. A small, named enum rather than a free ARGB int so
 * the settings row can cycle it like every other option and the stored value stays human-readable
 * in {@code config.json}. Add a colour by adding one constant – the cycle button and the renderer
 * both pick it up with no further change.
 */
public enum HighlightColor {

    RED("Red", 0xFFFF4040),
    ORANGE("Orange", 0xFFFFA030),
    YELLOW("Yellow", 0xFFFFE24B),
    GREEN("Green", 0xFF40E040),
    CYAN("Cyan", 0xFF00E5FF),
    BLUE("Blue", 0xFF4080FF),
    PURPLE("Purple", 0xFFB050FF),
    WHITE("White", 0xFFFFFFFF);

    private final String displayName;
    private final int argb;

    HighlightColor(String displayName, int argb) {
        this.displayName = displayName;
        this.argb = argb;
    }

    public String displayName() {
        return displayName;
    }

    /** Packed ARGB colour used for the box edges. */
    public int argb() {
        return argb;
    }

    /** The next colour in declaration order, wrapping – drives the settings cycle button. */
    public HighlightColor next() {
        HighlightColor[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
