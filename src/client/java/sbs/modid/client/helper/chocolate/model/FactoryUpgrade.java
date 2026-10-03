/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.chocolate.model;

/**
 * One purchasable slot in the Chocolate Factory, as the open menu described it.
 *
 * <p>Both numbers are read from that slot's own lore and both may be <b>unknown</b>, which is
 * {@code -1} rather than {@code 0}: an upgrade costing nothing and an upgrade whose cost could not
 * be found are different facts, and only the second one must be kept out of the ranking. The
 * request that produced this feature put it plainly - an upgrade whose gain is not in the lore is
 * left out, never guessed - and {@code -1} is what carries that through the code.
 *
 * @param slot   the slot index in the open menu, so the overlay can draw on it
 * @param name   the item's hover name, colour codes stripped
 * @param cost   what it costs in chocolate, or {@code -1} when the lore did not say
 * @param gain   added chocolate per second, or {@code -1} when the lore did not say
 */
public record FactoryUpgrade(int slot, String name, long cost, double gain) {

    /** The value both numeric fields carry when the lore did not yield them. */
    public static final long UNKNOWN = -1L;

    /** An upgrade with neither number read - still drawn, never ranked. */
    public static FactoryUpgrade unparsed(int slot, String name) {
        return new FactoryUpgrade(slot, name, UNKNOWN, UNKNOWN);
    }

    /**
     * Whether this upgrade can take part in the payback ranking at all.
     *
     * <p>Both numbers must be present and positive. A zero gain would divide to infinity and a
     * zero cost would rank first forever; neither is a thing the menu should produce, and if one
     * arrives it means the parse went wrong, which is exactly when the entry belongs out of the
     * ranking rather than at the top of it.
     */
    public boolean rankable() {
        return cost > 0 && gain > 0;
    }
}
