/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.logic;

import sbs.modid.client.helper.build.model.FreecamRules;

/**
 * The one "build input active" state: the Magic Stick Thingy in hand, or build freecam on
 * ({@link FreecamRules#buildInputActive}). The selection clicks, {@link BuildTargeting}'s step
 * deeper, the targeted-block outline, the live area preview and the help card all ask this - never
 * the stick or freecam directly - so the two ways in cannot drift apart.
 */
public final class BuildInput {

    private BuildInput() {
    }

    public static boolean active() {
        return FreecamRules.buildInputActive(MagicStickInput.holdingStick(), Freecam.mode());
    }
}
