/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.fishing;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.ModuleSubgroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.skills.fishing.model.FishingShareMode;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Fishing Share (Fishing): clickable hotspot shares and the rare sea creature alert. The runtime is
 * {@code logic/FishingShare}; this class is the settings page. Self-registered via
 * {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class FishingShareModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public FishingShareModule() {
    }

    @Override
    public String id() {
        return "fishing_share";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.SKILLS;
    }

    @Override
    public ModuleSubgroup subgroup() {
        return ModuleSubgroup.FISHING;
    }

    @Override
    public String displayName() {
        return "Fishing Share";
    }

    @Override
    public String description() {
        return "Fishing: share hotspots and rare sea creatures with your party in one click";
    }

    @Override
    public int accentColor() {
        return 0xFF3FB4FF;
    }

    private static SBSConfig.FishingShareSettings cfg() {
        return ConfigManager.getInstance().get().fishingShare;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        List<SettingRow> rows = new ArrayList<>();

        rows.add(SettingRow.toggle("Fishing Share", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                .describe("Clickable share lines for hotspots and rare sea creatures. Nothing is "
                        + "sent to anyone until you click, unless you turn on Auto below."));

        rows.add(SettingRow.label("§bHotspots"));
        rows.add(SettingRow.toggle("Offer Hotspot Share", () -> cfg().hotspotShare,
                        () -> { cfg().hotspotShare = !cfg().hotspotShare; save(); })
                .describe("When a hotspot comes into view while you fish, prints \"Share +15 "
                        + "Fishing Speed Hotspot: [To PARTY chat] or [To ALL chat]\". A click sends "
                        + "the buff and coordinates, tagged [SBS]."));

        rows.add(SettingRow.label("§bRare sea creatures"));
        rows.add(SettingRow.text("Rare Creatures", "Lord Jawbus, Thunder, ...", 400,
                        () -> cfg().rareCreatures,
                        v -> { cfg().rareCreatures = v; save(); })
                .describe("Comma-separated names that count as rare. Spelled as Hypixel names "
                        + "them; case does not matter."));
        rows.add(SettingRow.toggle("Big Title", () -> cfg().rareTitle,
                        () -> { cfg().rareTitle = !cfg().rareTitle; save(); })
                .describe("Puts the creature's name across the screen."));
        rows.add(SettingRow.soundToggle("Play Sound", () -> cfg().rareSound,
                        () -> { cfg().rareSound = !cfg().rareSound; save(); })
                .describe("The alarm tone on the mod's own audio output."));
        rows.add(SettingRow.enumOptions("Share to Party",
                        () -> cfg().rareShareMode == null ? FishingShareMode.CLICK : cfg().rareShareMode,
                        value -> { cfg().rareShareMode = value; save(); },
                        v -> v.name().charAt(0) + v.name().substring(1).toLowerCase(Locale.ROOT))
                .describe("Off: nothing. Click: a [To PARTY chat] button. Auto: sends your "
                        + "coordinates and the creature to party chat straight away."));
        return rows;
    }
}
