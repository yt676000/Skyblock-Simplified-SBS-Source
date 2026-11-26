/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.fishing.model;

/** How a fishing share (rare sea creature) reaches the party: never, on a click, or by itself. */
public enum FishingShareMode {
    /** Nothing is offered or sent. */
    OFF,
    /** A clickable line in local chat; nothing leaves the client until it is clicked. */
    CLICK,
    /** Sent to party chat straight away - the explicit opt-in. */
    AUTO
}
