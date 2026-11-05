/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.fishing.logic;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.skills.fishing.model.FishingCardSection;
import sbs.modid.client.ui.hud.card.CardSections;

import java.util.ArrayList;
import java.util.List;

/**
 * The fishing tracker card as a {@link CardSections.Card}: its sections, the stored order in
 * {@code fishing.sectionOrder}, and which section each toggle switches on. Reads the live config on
 * every call, so a profile switch while the editor is open never writes into a replaced config.
 */
public final class FishingCardLayout implements CardSections.Card {

    private static SBSConfig.FishingSettings cfg() {
        return ConfigManager.getInstance().get().fishing;
    }

    @Override
    public String title() {
        return "Fishing Tracker Sections";
    }

    @Override
    public List<CardSections.Section> sections() {
        return FishingCardSection.sections();
    }

    @Override
    public List<String> storedOrder() {
        return cfg().sectionOrder;
    }

    @Override
    public void storeOrder(List<String> ids) {
        cfg().sectionOrder = new ArrayList<>(ids);
    }

    @Override
    public boolean enabled(String id) {
        FishingCardSection section = FishingCardSection.byId(id);
        return section != null && enabled(cfg(), section);
    }

    /** Whether {@code section} is switched on: the one place the toggles map to sections. */
    public static boolean enabled(SBSConfig.FishingSettings cfg, FishingCardSection section) {
        return switch (section) {
            case CATCHES -> cfg.catchTracker;
            case RARE_DROPS -> cfg.rareDropTracker;
            case SHARDS -> cfg.shardTracker;
            case REEL_TIMER -> cfg.reelTimer;
            case BAITS_USED -> cfg.baitsUsedSection;
            case PROFIT -> cfg.profitTracker && (cfg.profitTimesFished || cfg.profitSession
                    || cfg.profitPerHour || cfg.profitTime);
        };
    }

    /** The sections in the player's order, switched-off ones included. */
    public static List<FishingCardSection> ordered(SBSConfig.FishingSettings cfg) {
        List<FishingCardSection> out = new ArrayList<>();
        for (String id : CardSections.resolve(cfg.sectionOrder, FishingCardSection.sections())) {
            FishingCardSection section = FishingCardSection.byId(id);
            if (section != null) {
                out.add(section);
            }
        }
        return out;
    }
}
