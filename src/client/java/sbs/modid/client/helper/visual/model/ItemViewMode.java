/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.visual.model;

/**
 * Where the Animation &amp; Scaling module's held-item size / offset applies: the first-person
 * hand, the third-person (player model) hand, or both.
 */
public enum ItemViewMode {
    BOTH("Both"),
    FIRST_PERSON("First Person"),
    THIRD_PERSON("Third Person");

    private final String label;

    ItemViewMode(String label) {
        this.label = label;
    }

    public String displayName() {
        return label;
    }

    public ItemViewMode next() {
        ItemViewMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    public boolean firstPerson() {
        return this != THIRD_PERSON;
    }

    public boolean thirdPerson() {
        return this != FIRST_PERSON;
    }
}
