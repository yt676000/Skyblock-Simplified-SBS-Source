/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.sacks.model;

/** How the Sack Overlay orders its rows. Three exclusive values, so: a segmented switch. */
public enum SackSort {

    /** Line value, largest first - "what is actually worth taking out of here". The default. */
    VALUE("Value"),

    /** Stored amount, largest first - "what is filling this sack up". */
    AMOUNT("Amount"),

    /** Item name, A to Z - the only order that does not move when prices do. */
    NAME("Name");

    private final String displayName;

    SackSort(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public static java.util.List<String> labels() {
        java.util.List<String> out = new java.util.ArrayList<>(values().length);
        for (SackSort sort : values()) {
            out.add(sort.displayName());
        }
        return out;
    }
}
