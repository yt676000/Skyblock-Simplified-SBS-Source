/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.fishing.model;

import sbs.modid.client.ui.hud.card.CardSections;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The sections of the fishing tracker card, in their default order. The ids are what the player's
 * stored order is keyed on ({@code fishing.sectionOrder}): never rename one - a renamed id drops that
 * section to the end of every existing layout. Declaration order is the default order.
 */
public enum FishingCardSection {
    CATCHES("catches", "Catches"),
    RARE_DROPS("rare_drops", "Rare Drops"),
    SHARDS("shards", "Shards"),
    REEL_TIMER("reel_timer", "Reel Timer"),
    BAITS_USED("baits_used", "Baits Used"),
    PROFIT("profit", "Profit");

    private static final List<CardSections.Section> SECTIONS;

    static {
        List<CardSections.Section> sections = new ArrayList<>();
        for (FishingCardSection section : values()) {
            sections.add(new CardSections.Section(section.id, section.label));
        }
        SECTIONS = Collections.unmodifiableList(sections);
    }

    private final String id;
    private final String label;

    FishingCardSection(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    /** The section with {@code id}, or null. */
    public static FishingCardSection byId(String id) {
        for (FishingCardSection section : values()) {
            if (section.id.equals(id)) {
                return section;
            }
        }
        return null;
    }

    /** Every section in default order, as the shared card helper's catalogue. */
    public static List<CardSections.Section> sections() {
        return SECTIONS;
    }
}
