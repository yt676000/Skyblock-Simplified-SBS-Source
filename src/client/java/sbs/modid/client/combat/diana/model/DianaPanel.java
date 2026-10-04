/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.model;

/**
 * The two Diana HUD cards a line can be placed on.
 *
 * <p>Deliberately not the {@code HudElement} constants themselves, so the row model can be built and
 * tested without the HUD catalogue; {@code DianaHud} maps one to the other. The config keeps one
 * list per card rather than storing these names, but they are still never renamed.
 */
public enum DianaPanel {
    TRACKER("Diana", "Tracker card"),
    CREATURES("Mythological", "Creature card");

    private final String title;
    private final String editorName;

    DianaPanel(String title, String editorName) {
        this.title = title;
        this.editorName = editorName;
    }

    /** The heading drawn on the card when its title is on. */
    public String title() {
        return title;
    }

    /** What the line editor calls this card. */
    public String editorName() {
        return editorName;
    }

    public DianaPanel other() {
        return this == TRACKER ? CREATURES : TRACKER;
    }
}
