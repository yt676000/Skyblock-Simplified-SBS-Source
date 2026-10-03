/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.kuudra.model;

import net.minecraft.world.phys.Vec3;

/**
 * Which edge of the arena Kuudra has surfaced at.
 *
 * <p><b>It is worth a call-out because you cannot see him coming.</b> In the last phase Kuudra dives
 * and comes back up at one of the four sides, and the difference between being on the right side and
 * the wrong side is the whole damage window. Nothing in chat says where - the only signal is that his
 * entity is suddenly over there - so the side is read off his position.
 *
 * <p>The four names are as seen standing on the platform facing the ballista, which is the way
 * everybody is facing anyway. The thresholds sit well outside the middle of the arena (roughly
 * {@code x=-100, z=-108}), so the answer is {@link #UNKNOWN} while he is submerged in the centre
 * rather than flickering between two sides.
 */
public enum KuudraSide {

    UNKNOWN("?"),
    FRONT("Front"),
    BACK("Back"),
    LEFT("Left"),
    RIGHT("Right");

    private final String displayName;

    KuudraSide(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** The side {@code pos} lies on, or {@link #UNKNOWN} while it is near the middle. */
    public static KuudraSide of(Vec3 pos) {
        if (pos.x < -128) {
            return RIGHT;
        }
        if (pos.x > -72) {
            return LEFT;
        }
        if (pos.z > -84) {
            return FRONT;
        }
        if (pos.z < -132) {
            return BACK;
        }
        return UNKNOWN;
    }
}
