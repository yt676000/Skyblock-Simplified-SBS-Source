/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.damage.model;


/** Compact damage numbers shared by the damage HUD cards: {@code 1.4M}, {@code 812.3K}, {@code 940}. */
public final class DamageFormat {

    private DamageFormat() {
    }

    public static String compact(double damage) {
        return sbs.modid.client.core.util.NumberDisplay.format(damage);
    }
}
