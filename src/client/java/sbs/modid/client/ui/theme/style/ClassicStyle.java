/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.theme.style;

import sbs.modid.client.ui.theme.SBSTheme;

/**
 * The original SBS look: rounded panels, vertical gradients, a visible border on every card, a soft
 * outer glow.
 *
 * <p>Deliberately a pure geometry reset with no material and no palette - it must leave the colours
 * exactly as the theme engine derived them, because "Classic" is the identity of the whole system.
 * Every other style is defined as a departure from what this one leaves behind.
 */
public final class ClassicStyle implements StyleDefinition {

    @Override
    public String displayName() {
        return "Classic";
    }

    @Override
    public String tagline() {
        return "Rounded windows, gradient fills, a border on every card.";
    }

    @Override
    public void applySurfaces() {
        SBSTheme.PANEL_CORNER = 7;
        SBSTheme.CORNER_RADIUS = 5;
        SBSTheme.HUD_CORNER = 7;
        SBSTheme.SLOT_CORNER = 5;
    }
}
