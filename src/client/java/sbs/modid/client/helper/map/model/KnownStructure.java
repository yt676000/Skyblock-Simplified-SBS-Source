/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.map.model;

import sbs.modid.client.core.location.hollows.HollowsStructure;

/**
 * One structure as the Crystal Hollows map draws it: where, and how much to trust it.
 *
 * @param own           found by the player walking into it
 * @param confirmations for a shared sighting, how many players reported it; {@code 0} for an own one
 * @param confirmed     own, or shared with at least {@link SharedSighting#CONFIRMATIONS_NEEDED}
 */
public record KnownStructure(HollowsStructure structure, int x, int y, int z, boolean own,
                             int confirmations, boolean confirmed) {
}
