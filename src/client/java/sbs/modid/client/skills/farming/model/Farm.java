/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.farming.model;

import java.util.ArrayList;
import java.util.List;

/**
 * One farm the player marked for the Lane End Warning: a name, the Garden plot it is on, and any number
 * of {@link Lane}s, each with its own two ends. Plain fields so Gson stores it as written.
 */
public final class Farm {

    /** Assigned once by the store and never reused; the key "show only this farm" remembers. */
    public int id;
    public String name = "";
    /** Hypixel's plot number ({@code GardenPlotCatalog}), {@code -1} outside the plot grid. */
    public int plot = -1;
    public List<Lane> lanes = new ArrayList<>();

    public Farm() {
    }

    public Farm(int id, String name, int plot) {
        this.id = id;
        this.name = name;
        this.plot = plot;
    }

    /** "Plot 7", "the house plot", or "no plot". */
    public String plotLabel() {
        return plot > 0 ? "Plot " + plot : plot == 0 ? "house" : "no plot";
    }
}
