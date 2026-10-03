/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.loadouts;

/**
 * Whether the {@link LoadoutHud} widget is shown.
 *
 * <p>An enum rather than a boolean because it started as one: the first build drew a card without
 * the player model and offered a second mode that added it. The widget is now the SBS Loadouts card
 * one to one, model included, so there is nothing left to choose - but {@link #MODEL} stays a
 * constant, because Gson stores enums by name and a config that already says {@code MODEL} would
 * otherwise deserialize to null and silently switch the widget off. It reads as on, and
 * {@link #next} never selects it again.
 */
public enum LoadoutWidgetMode {

    OFF("Off"),
    CARD("On"),
    /** Legacy value only – see the class docs. Behaves exactly like {@link #CARD}. */
    MODEL("On");

    private final String displayName;

    LoadoutWidgetMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public LoadoutWidgetMode next() {
        return this == OFF ? CARD : OFF;
    }

    /** Whether the widget is on – also what keeps the shared cache fed while the grid is off. */
    public boolean shows() {
        return this != OFF;
    }
}
