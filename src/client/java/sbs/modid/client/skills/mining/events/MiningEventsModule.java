/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.events;

import net.minecraft.client.Minecraft;
import sbs.modid.client.core.alert.AlertChannelRows;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.ModuleSubgroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.skills.mining.events.logic.MiningEventHistory;
import sbs.modid.client.skills.mining.events.logic.MiningEventTracker;
import sbs.modid.client.skills.mining.events.model.MiningEvent;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.ui.HudEditorScreen;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Mining Event Timer (Skills > Mining): the Dwarven Mines / Crystal Hollows lobby event that is
 * running, how long it has left, and when the next one starts - with how sure that is. Display only.
 * Self-registered via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class MiningEventsModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public MiningEventsModule() {
    }

    @Override
    public String id() {
        return "mining_events";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.SKILLS;
    }

    @Override
    public ModuleSubgroup subgroup() {
        return ModuleSubgroup.MINING;
    }

    @Override
    public String displayName() {
        return "Mining Event Timer";
    }

    @Override
    public String description() {
        return "The Dwarven Mines / Crystal Hollows event running in your lobby, its time left, and "
                + "when the next one starts";
    }

    @Override
    public int accentColor() {
        return 0xFF55CCFF;
    }

    private static SBSConfig.MiningEventSettings cfg() {
        return ConfigManager.getInstance().get().miningEvents;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        List<SettingRow> rows = new ArrayList<>();
        rows.add(SettingRow.toggle("Mining Event Timer", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                .describe("Follows the lobby events of the Dwarven Mines and the Crystal Hollows (2x Powder, "
                        + "Gone with the Wind, Better Together, Goblin Raid, Raffle, Mithril Gourmand) from "
                        + "their chat lines. Default: on."));
        rows.add(SettingRow.toggle("Mining Events HUD", () -> cfg().hud,
                        () -> { cfg().hud = !cfg().hud; save(); })
                .describe("A card with the running event and its time left, and when the next one starts. "
                        + "The next start always carries how sure it is: KNOWN when the game announced it, "
                        + "ESTIMATED from what you watched in this lobby, UNKNOWN otherwise. Default: on."));
        rows.add(SettingRow.toggle("Show Other Island", () -> cfg().otherIsland,
                        () -> { cfg().otherIsland = !cfg().otherIsland; save(); })
                .describe("Adds a line with the latest event seen on the other mining island. Default: on."));
        rows.add(SettingRow.button("Move / Resize Mining Events Card", () -> Minecraft.getInstance()
                        .setScreenAndShow(new HudEditorScreen(new HudElement[] {HudElement.MINING_EVENTS},
                                "Edit Mining Events Card")))
                .describe("Opens the editor where you drag the card anywhere on the screen."));

        rows.add(SettingRow.label("— Alerts —"));
        for (MiningEvent event : MiningEvent.values()) {
            if (event == MiningEvent.UNKNOWN) {
                continue;
            }
            rows.add(listToggle(event.displayName() + " Started", () -> cfg().startAlerts, event,
                    "Alerts when " + event.displayName() + " starts in your lobby. Default: "
                            + (event == MiningEvent.TWO_X_POWDER ? "on." : "off.")));
        }
        for (MiningEvent event : MiningEvent.values()) {
            if (event == MiningEvent.UNKNOWN) {
                continue;
            }
            rows.add(listToggle(event.displayName() + " Ends Soon", () -> cfg().endingAlerts, event,
                    "Alerts about a minute before " + event.displayName() + " ends. Needs a remaining time, "
                            + "so it stays quiet until one is known. Default: off."));
        }
        rows.add(SettingRow.toggle("Next Event Soon", () -> cfg().nextAlert,
                        () -> { cfg().nextAlert = !cfg().nextAlert; save(); })
                .describe("Alerts when the next start is estimated to be about 2 minutes away. Only fires once "
                        + MiningEventHistory.MIN_INTERVALS + " event cycles have been watched on that island. "
                        + "Default: off."));
        rows.add(SettingRow.toggle("Powder Ghast Spawned", () -> cfg().ghastAlert,
                        () -> { cfg().ghastAlert = !cfg().ghastAlert; save(); })
                .describe("Alerts when the Powder Ghast spawns in your lobby. Default: off."));
        rows.addAll(AlertChannelRows.forAlert("mining_events", "a mining event alert fires",
                () -> cfg().alertChannels, mask -> { cfg().alertChannels = mask; save(); }));

        rows.add(SettingRow.label("— Data —"));
        rows.add(SettingRow.toggle("Read Remaining Time From Sidebar", () -> cfg().readScoreboard,
                        () -> { cfg().readScoreboard = !cfg().readScoreboard; save(); })
                .describe("Resyncs the countdown from the sidebar when it shows the event's time left. How "
                        + "the sidebar shows an event has not been confirmed yet; without it the time left is "
                        + "estimated from events you watched end. Default: on."));
        rows.add(SettingRow.toggle("Log Event Lines", () -> cfg().captureLog,
                        () -> { cfg().captureLog = !cfg().captureLog; save(); })
                .describe("Writes event chat lines and new sidebar lines in the two mining islands to the game "
                        + "log, so the sidebar format can be confirmed. Nothing leaves your PC. Default: on."));
        rows.add(SettingRow.label(status()));
        return rows;
    }

    /** One toggle per event over a list of event ids. */
    private static SettingRow listToggle(String label, Supplier<List<String>> ids, MiningEvent event,
                                         String description) {
        return SettingRow.toggle(label, () -> ids.get().contains(event.name()), () -> {
            if (!ids.get().remove(event.name())) {
                ids.get().add(event.name());
            }
            save();
        }).describe(description);
    }

    /** What the history supports, in the player's words. */
    private static String status() {
        MiningEventHistory history = MiningEventTracker.getInstance().historyIfLoaded();
        if (history == null) {
            return "§8History loads when you enter the Dwarven Mines or the Crystal Hollows";
        }
        return "§8" + history.size() + " events seen in 14 days; cycles watched: Dwarven Mines "
                + history.cycles(MiningEventTracker.DWARVEN_MINES).size() + ", Crystal Hollows "
                + history.cycles(MiningEventTracker.CRYSTAL_HOLLOWS).size() + " (need "
                + MiningEventHistory.MIN_INTERVALS + ")";
    }
}
