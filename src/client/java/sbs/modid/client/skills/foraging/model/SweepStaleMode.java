/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.foraging.model;

/**
 * What the Sweep card does once no chop has been reported for a while.
 *
 * <p>The value never stops being <i>true</i> - it is what the last chop really was - so "stale"
 * here is about how loudly the card should say that it is not live, not about whether the number
 * can be trusted.
 *
 * <ul>
 *   <li>{@link #GREY} – the card stays, its values drop to the muted colour. The default: greying
 *       is how the rest of the mod says "this is not live".</li>
 *   <li>{@link #HIDE} – the card disappears until the next chop.</li>
 *   <li>{@link #KEEP} – never greys, for comparing gear between swings.</li>
 * </ul>
 */
public enum SweepStaleMode {

    GREY("Grey out"),
    HIDE("Hide"),
    KEEP("Keep showing");

    private final String displayName;

    SweepStaleMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
