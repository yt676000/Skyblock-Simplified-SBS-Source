/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.dragonprofit.model;

import java.util.Locale;

/**
 * The End dragons as "☬ The &lt;Type&gt; Dragon has spawned!" names them (all seven CONFIRMED), with
 * the fragment id each drops. The fragment ids are from {@code sbs-skyblock-items.json}; that a
 * dragon drops its own fragment is WIKI certainty until the probe has seen it.
 */
public enum DragonType {
    PROTECTOR("Protector"),
    OLD("Old"),
    WISE("Wise"),
    UNSTABLE("Unstable"),
    YOUNG("Young"),
    STRONG("Strong"),
    SUPERIOR("Superior");

    private final String label;

    DragonType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public String fragmentId() {
        return name() + "_FRAGMENT";
    }

    /** "Wise", "WISE" or "wise" to {@link #WISE}; {@code null} for anything else. */
    public static DragonType of(String word) {
        if (word == null) {
            return null;
        }
        try {
            return valueOf(word.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
