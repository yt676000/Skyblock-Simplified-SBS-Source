/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.hideplayers;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;

/**
 * Hide Nearby Players (Visuals): other players close to you are not drawn, so a crowded hub or farm
 * stays readable. Rendering only - see {@code logic/PlayerHiding}. Self-registered via
 * {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class HidePlayersModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public HidePlayersModule() {
    }

    @Override
    public String id() {
        return "hide_players";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.VISUALS;
    }

    @Override
    public String displayName() {
        return "Hide Nearby Players";
    }

    @Override
    public String description() {
        return "Stop drawing other players close to you - party members and NPCs stay";
    }

    @Override
    public int accentColor() {
        return 0xFF9A8CFF;
    }

    private static SBSConfig.HidePlayersSettings cfg() {
        return ConfigManager.getInstance().get().hidePlayers;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Hide Nearby Players", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Other players close to you are not drawn - no body, armour, name "
                                + "or shadow - so a crowded hub or farm stays readable. Only how they "
                                + "look changes: you can still hit, bump into and right-click them. "
                                + "NPCs are never hidden. Default: off."),
                SettingRow.toggle("Hide Players Near Me", () -> cfg().nearMe,
                        () -> { cfg().nearMe = !cfg().nearMe; save(); })
                        .describe("Hides players within the distance below of you. Turn it off to use "
                                + "only the NPC mode. Default: on."),
                SettingRow.intField("Hide Within", 1, 64, () -> cfg().radius,
                        value -> { cfg().radius = value; save(); }, " blocks")
                        .describe("Players closer than this are hidden; they reappear as they walk "
                                + "out of range. Default: 5 blocks."),
                SettingRow.toggle("Hide Everywhere", () -> cfg().everywhere,
                        () -> { cfg().everywhere = !cfg().everywhere; save(); })
                        .describe("Hides every other player you can see, not just those nearby - "
                                + "the exceptions below still apply. Default: off."),
                SettingRow.keybind("Hide Players Key", () -> cfg().toggleKey,
                        value -> { cfg().toggleKey = value; save(); })
                        .describe("Press to pause or resume hiding without opening the settings; a "
                                + "chat line says which way it went. Unbound by default."),

                SettingRow.label("— Around NPCs —"),
                SettingRow.toggle("Hide Players Near NPCs", () -> cfg().nearNpcs,
                        () -> { cfg().nearNpcs = !cfg().nearNpcs; save(); })
                        .describe("Hides players crowding an NPC you are standing near (within 10 "
                                + "blocks), so you can see the NPC - handy at the Bazaar or the Banker "
                                + "in the Hub. Only how they look changes: clicking still hits whoever "
                                + "vanilla says you are pointing at, never the NPC behind them. Works "
                                + "alongside the mode above. Default: off."),
                SettingRow.intField("Around NPC Within", 1, 16, () -> cfg().npcRadius,
                        value -> { cfg().npcRadius = value; save(); }, " blocks")
                        .describe("Players this close to the NPC are hidden. Default: 3 blocks."),
                SettingRow.toggle("Step-Aside Hint", () -> cfg().npcHint,
                        () -> { cfg().npcHint = !cfg().npcHint; save(); })
                        .describe("When your crosshair points through a hidden player at the NPC "
                                + "behind them, a short line under the crosshair says so - your click "
                                + "would reach the player, so step aside. Default: on."),

                SettingRow.label("— Always show —"),
                SettingRow.toggle("Keep Party Members", () -> cfg().keepParty,
                        () -> { cfg().keepParty = !cfg().keepParty; save(); })
                        .describe("Players in your party stay visible. Default: on."),
                SettingRow.toggle("Keep Dungeon Team", () -> cfg().keepDungeonTeam,
                        () -> { cfg().keepDungeonTeam = !cfg().keepDungeonTeam; save(); })
                        .describe("Your dungeon teammates stay visible, even when the party roster "
                                + "missed one. Default: on."),
                SettingRow.toggle("Keep Trusted Players", () -> cfg().keepTrusted,
                        () -> { cfg().keepTrusted = !cfg().keepTrusted; save(); })
                        .describe("Players you tagged Trusted in Player Notes stay visible. "
                                + "Default: on."),

                SettingRow.label("— Where it applies —"),
                SettingRow.toggle("Hide In Combat Too", () -> cfg().hideInCombat,
                        () -> { cfg().hideInCombat = !cfg().hideInCombat; save(); })
                        .describe("Normally nothing is hidden in a dungeon, in Kuudra's Hollow or "
                                + "while your slayer boss is up, because there the players around "
                                + "you are information. Turn this on to hide there as well. "
                                + "Default: off."));
    }
}
