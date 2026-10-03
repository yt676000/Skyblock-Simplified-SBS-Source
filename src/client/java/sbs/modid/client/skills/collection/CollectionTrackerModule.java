/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.collection;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.ui.hud.edit.ui.HudEditorScreen;
import sbs.modid.client.ui.settings.SettingRow;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;

import java.util.List;

/**
 * Collection Tracker module (Skills): a movable HUD card that auto-detects which collection you are
 * currently working on (via the tab-list Collection widget) and shows its exact counter, a progress
 * bar to the next tier with current/required, and the session gain – a live farming counter,
 * universal for every collection. Self-registered via
 * {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class CollectionTrackerModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public CollectionTrackerModule() {
    }

    @Override
    public String id() {
        return "collection_tracker";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.SKILLS;
    }

    @Override
    public String displayName() {
        return "Collection Tracker";
    }

    @Override
    public String description() {
        return "Auto-detects the collection you farm: exact counter + progress to the next tier";
    }

    @Override
    public int accentColor() {
        return 0xFF7ED957;
    }

    private static SBSConfig.CollectionTrackerSettings cfg() {
        return ConfigManager.getInstance().get().collectionTracker;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Collection Tracker", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("A card with the collection you are currently farming: total "
                                + "count and progress to the next collection tier. Turn on the "
                                + "Collection tab widget (/widgets) and sack messages in Hypixel's "
                                + "settings so it has something to read."),
                SettingRow.label("Total from the tab Collection widget OR the Collections menu"),
                SettingRow.label("Counts live from sack messages (enable them in Hypixel settings)"),

                SettingRow.toggle("Show Rate (/h)", () -> cfg().showRate,
                        () -> { cfg().showRate = !cfg().showRate; save(); })
                        .describe("Adds an items-per-hour line, measured from how fast the counter "
                                + "has been rising this session."),

                // Pick which collection to keep on the HUD; empty = auto-detect the one being farmed.
                SettingRow.text("Pin Collection", "name (empty = auto)", 32,
                        () -> cfg().pinnedCollection == null ? "" : cfg().pinnedCollection,
                        v -> { cfg().pinnedCollection = v == null ? "" : v.trim(); save(); })
                        .describe("Lock the card to one collection by name (e.g. Wheat or "
                                + "Mithril). Leave it empty and the card follows whatever you are "
                                + "currently collecting."),
                SettingRow.label("Empty = auto-detect; e.g. \"Wheat\", \"Mithril\", \"Hard Stone\""),

                SettingRow.button("Move / Resize Overlay", () -> open(new HudEditorScreen(
                        new HudElement[] {HudElement.COLLECTION_TRACKER}, "Edit Collection Tracker")))
                        .describe("Opens the editor where you drag the collection card anywhere on "
                                + "the screen and scale it."),
                SettingRow.label("Shows while a tracked collection counter is rising"));
    }

    private static void open(net.minecraft.client.gui.screens.Screen screen) {
        net.minecraft.client.Minecraft.getInstance().setScreenAndShow(screen);
    }
}
