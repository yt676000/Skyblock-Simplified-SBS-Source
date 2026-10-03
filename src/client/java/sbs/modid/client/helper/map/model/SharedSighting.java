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
 * A structure location somebody else reported for this lobby, with how many players have confirmed
 * it. Fed by Structure Sharing through {@code HollowsMapStore.offerShared}; nothing else creates one.
 */
public record SharedSighting(HollowsStructure structure, int x, int y, int z, int confirmations,
                             long receivedAt) {

    /** Confirmations a shared sighting needs before the map draws it as solid. */
    public static final int CONFIRMATIONS_NEEDED = 2;

    public boolean confirmed() {
        return confirmations >= CONFIRMATIONS_NEEDED;
    }
}
