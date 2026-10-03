/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.notes;

import sbs.modid.client.core.alert.AlertChannelRows;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.social.notes.logic.PlayerNotesCommands;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.ArrayList;
import java.util.List;

/**
 * Player Notes (Party &amp; Chat): remember who you would take again and who you would not, and be
 * told when they show up. The notes, the warnings and the commands live in {@code logic/}, the notes
 * screen in {@code ui/}; this class is the card and its rows.
 *
 * <p>Private and local by design - see {@code PlayerNotesStore}. Read-only toward the game: a
 * warning is a line in your own chat, and its buttons do nothing until you click them.
 * Self-registered via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class PlayerNotesModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public PlayerNotesModule() {
    }

    @Override
    public String id() {
        return "player_notes";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.PARTY_CHAT;
    }

    @Override
    public String displayName() {
        return "Player Notes";
    }

    @Override
    public String description() {
        return "Private notes on players - warns when someone you tagged joins your party";
    }

    @Override
    public int accentColor() {
        return 0xFFE0C25C;
    }

    private static SBSConfig.PlayerNotesSettings cfg() {
        return ConfigManager.getInstance().get().playerNotes;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        List<SettingRow> rows = new ArrayList<>(List.of(
                SettingRow.toggle("Player Notes", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Keep a note on other players - Trusted, Neutral or Avoid, plus a "
                                + "line of text - and get a warning when one of them shows up. Your "
                                + "notes are private: they are stored only on this computer "
                                + "(config/sbs/data/player_notes.json), are never sent to the SBS "
                                + "server, IRC or any chat, and are left out when you share your "
                                + "settings. Off keeps the notes and stops the warnings. Default: on."),
                SettingRow.button("Open Player Notes", () -> PlayerNotesCommands.openScreen(""))
                        .describe("Opens the list of your notes: search, filter by tag, edit or "
                                + "delete them, and add a player by name. Same as /sbs notes."),
                SettingRow.label("/sbs note <name> <text>  ·  /sbs avoid <name> [reason]  ·  /sbs trust <name>"),

                SettingRow.label("— When to warn you —"),
                SettingRow.toggle("Warn On Party Join", () -> cfg().warnPartyJoin,
                        () -> { cfg().warnPartyJoin = !cfg().warnPartyJoin; save(); })
                        .describe("A noted player joining your party - or already in the party you "
                                + "join - prints one line with their tag and note, once per party. For "
                                + "an Avoid player, when you lead the party, the line has a [KICK] "
                                + "button; nothing happens until you click it. Default: on."),
                SettingRow.toggle("Warn In Dungeon Team", () -> cfg().warnDungeonTeam,
                        () -> { cfg().warnDungeonTeam = !cfg().warnDungeonTeam; save(); })
                        .describe("Checks your dungeon team from the tab list when a run starts and "
                                + "warns about noted players on it - also catches teammates the party "
                                + "chat never named. Not repeated for someone already warned about "
                                + "this party. Default: on."),
                SettingRow.toggle("Warn When Nearby", () -> cfg().warnNearby,
                        () -> { cfg().warnNearby = !cfg().warnNearby; save(); })
                        .describe("Warns when a noted player comes within render distance, once per "
                                + "lobby. Can get busy in a hub, which is why it starts off. "
                                + "Default: off."),
                SettingRow.toggle("Note Nametag Marker", () -> cfg().nametagMarker,
                        () -> { cfg().nametagMarker = !cfg().nametagMarker; save(); })
                        .describe("Puts a small mark in front of a noted player's name above their "
                                + "head: a red cross for Avoid, a green tick for Trusted, nothing for "
                                + "Neutral. Hidden while Streamer Mode is on, so a stream never shows "
                                + "who you tagged. Default: off."),

                SettingRow.label("— How you are warned —")));
        rows.addAll(AlertChannelRows.forAlert("player_note", "a noted player shows up",
                () -> cfg().channels, value -> { cfg().channels = value; save(); }));
        rows.add(SettingRow.label("Chat = the warning line with its [NOTE] / [KICK] buttons"));
        return rows;
    }
}
