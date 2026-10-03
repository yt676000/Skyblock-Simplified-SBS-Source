/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.wizard;

/** The two flows the overlay serves. One system, because the only difference is which pages. */
public enum WizardMode {

    /** The basics, once, on first run. Never capped - it is short by construction. */
    ONBOARDING,

    /**
     * What changed since the version the player last saw. Capped, because a player several versions
     * behind must not be handed fifteen pages.
     */
    SHOWCASE
}
