/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.party;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.social.party.logic.PartyTracker;
import sbs.modid.client.ui.settings.SettingRow;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;

import java.util.List;

/**
 * Party highlight module (Quality of Life): highlights your Hypixel party members among the players on the
 * island with a through-wall box + nametag in a chosen colour. The roster is read from the party
 * chat notifications by {@link PartyTracker}; self-registered via
 * {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class PartyHighlightModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public PartyHighlightModule() {
    }

    @Override
    public String id() {
        return "party_highlight";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.PARTY_CHAT;
    }

    @Override
    public String displayName() {
        return "Party Highlight";
    }

    @Override
    public String description() {
        return "Highlight your party members on the island (box + nametag, through walls)";
    }

    @Override
    public int accentColor() {
        return 0xFF57D977;
    }

    private static SBSConfig.PartyHighlightSettings cfg() {
        return ConfigManager.getInstance().get().partyHighlight;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    /**
     * One "hide the highlight in this boss room" toggle. The row reads ON when the floor is <i>hidden</i>,
     * which is what the label says ("Hide in ..."), so the checked state matches the sentence.
     */
    private static SettingRow bossToggle(int floor, String bossLabel) {
        return SettingRow.toggle("Hide in " + bossLabel,
                        () -> cfg().hiddenBossFloors.contains(floor),
                        () -> {
                            if (!cfg().hiddenBossFloors.remove(floor)) {
                                cfg().hiddenBossFloors.add(floor);
                            }
                            save();
                        })
                .describe("No party boxes, pointer lines or nametags inside the " + bossLabel
                        + " boss room");
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Party Highlight", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .anchor("party_highlight", "party_esp")
                        .describe("Shows your party members through walls: a box around them, "
                                + "their name, and optionally a line pointing to them. The member "
                                + "list is read from party chat; type /pl once to seed it fully."),
                SettingRow.label("Roster is read from party chat (joined/left); /pl seeds the full list"),

                SettingRow.enumOptions("Color", () -> cfg().color,
                        value -> { cfg().color = value; save(); }, v -> v.displayName())
                        .describe("The color of the boxes, names and lines. Click to cycle."),
                SettingRow.toggle("Show Box", () -> cfg().showBox,
                        () -> { cfg().showBox = !cfg().showBox; save(); })
                        .describe("The box drawn around each party member, visible through "
                                + "walls."),
                SettingRow.toggle("Show Nametag", () -> cfg().showNametag,
                        () -> { cfg().showNametag = !cfg().showNametag; save(); })
                        .describe("The member's name drawn above the box, visible through walls."),
                SettingRow.toggle("Show Pointer Lines", () -> cfg().showTracer,
                        () -> { cfg().showTracer = !cfg().showTracer; save(); })
                        .anchor("show_tracers")
                        .describe("A line from your crosshair to each member - follow it to find "
                                + "them when they are out of sight."),
                SettingRow.label("A line from your crosshair to each member - finds them through walls"),

                SettingRow.label("§7Hide in boss rooms - the fights where the boxes cover the boss:"),
                bossToggle(1, "Bonzo (F1)"),
                bossToggle(2, "Scarf (F2)"),
                bossToggle(3, "The Professor (F3)"),
                bossToggle(4, "Thorn (F4)"),
                bossToggle(5, "Livid (F5)"),
                bossToggle(6, "Sadan (F6)"),
                bossToggle(7, "Necron (F7)"),
                SettingRow.label("Applies to the Master Mode floor too - M5 is Livid's room as much as F5"),

                SettingRow.button("Reset Party List", () -> PartyTracker.getInstance().reset())
                        .describe("Forgets the remembered member list, e.g. after it got out of "
                                + "sync. It refills itself from party chat (or /pl)."),
                SettingRow.label("Roster auto-clears when >5min stale (offline-kick window)"));
    }
}
