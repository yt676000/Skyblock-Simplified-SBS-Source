/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.notes.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Player;
import sbs.modid.client.core.alert.AlertChannel;
import sbs.modid.client.core.alert.AlertChannels;
import sbs.modid.client.core.alert.Alerts;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.dungeons.run.model.DungeonTeamClasses;
import sbs.modid.client.social.chat.logic.SBSChat;
import sbs.modid.client.social.notes.model.NoteTag;
import sbs.modid.client.social.notes.model.PlayerNote;
import sbs.modid.client.social.notes.model.PlayerNoteBook;
import sbs.modid.client.social.party.logic.PartyTracker;

import java.util.HashSet;
import java.util.Set;

/**
 * Tells the player when somebody they wrote a note about turns up: joins the party, is on the
 * dungeon team, or (optionally) comes within render distance.
 *
 * <p><b>Read-only toward the game.</b> The warning is a chat line in the player's own client and
 * nothing more. It carries buttons - [NOTE] fills the chat box with the note command so the player
 * can edit it, [KICK] runs {@code /p kick <name>} - and neither does anything until the player
 * clicks it. Nothing here ever sends a command, a chat message or a request by itself.
 *
 * <p><b>Once per party session, per player.</b> A party join and the same person being on the
 * dungeon team a minute later are one warning, not two. The set clears when the party ends
 * ({@link PartyTracker} calls {@link #onPartyEnded}); a dungeon teammate who was never a tracked
 * party member is released again when the run ends. Nearby warnings have their own set, cleared on
 * every world change, and skip anyone already warned about through the party.
 *
 * <p>Where names come from: {@link PartyTracker} (the party chat parser - there is deliberately no
 * second one here) and {@link DungeonTeamClasses#runRoster()} (the tab list). Both have already
 * reduced the text to a bare username; the note book validates it again anyway, because chat text
 * comes from other players and the name ends up inside a clickable command.
 */
public final class PlayerNotesWarner {

    private static final PlayerNotesWarner INSTANCE = new PlayerNotesWarner();

    /** The world scan (dungeon team, nearby players, UUID learning) runs at most this often. */
    private static final long SCAN_MS = 1000L;

    /** Lower-cased names already warned about in this party session. */
    private final Set<String> partyWarned = new HashSet<>();
    /** The subset of {@link #partyWarned} that came from the dungeon team, not the party. */
    private final Set<String> dungeonOnly = new HashSet<>();
    /** Lower-cased names warned about as nearby since the last world change. */
    private final Set<String> nearbyWarned = new HashSet<>();

    private long lastScan;
    private ClientLevel lastLevel;

    private enum Source {
        PARTY("joined your party"),
        DUNGEON("is on your dungeon team"),
        NEARBY("is nearby");

        private final String phrase;

        Source(String phrase) {
            this.phrase = phrase;
        }
    }

    private PlayerNotesWarner() {
    }

    public static PlayerNotesWarner getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.PlayerNotesSettings cfg() {
        return ConfigManager.getInstance().get().playerNotes;
    }

    // ------------------------------------------------------------------ party signals

    /** A name newly entered the party roster (join line or /pl roster line). */
    public void onPartyMember(String name) {
        SBSConfig.PlayerNotesSettings cfg = cfg();
        if (!cfg.enabled || !cfg.warnPartyJoin || !PlayerNoteBook.isValidName(name)) {
            return;
        }
        String key = PlayerNoteBook.key(name);
        if (partyWarned.contains(key)) {
            return;
        }
        PlayerNote note = PlayerNotesStore.getInstance().book().match(name, PlayerLookup.uuidOf(name));
        if (note != null) {
            partyWarned.add(key);
            dungeonOnly.remove(key);
            warn(note, Source.PARTY);
        }
    }

    /** The party is over: the next party is a new session. */
    public void onPartyEnded() {
        partyWarned.clear();
        dungeonOnly.clear();
    }

    // ------------------------------------------------------------------ tick

    /** Game-tick entry: saves the notes file when due, then the throttled world scan. */
    public void tick(Minecraft minecraft) {
        PlayerNotesStore.getInstance().tick();
        ClientLevel level = minecraft.level;
        if (level != lastLevel) {
            lastLevel = level;
            nearbyWarned.clear();   // a world change is a new lobby: nearby starts over
        }
        long now = System.currentTimeMillis();
        if (level == null || minecraft.player == null || now - lastScan < SCAN_MS) {
            return;
        }
        lastScan = now;
        SBSConfig.PlayerNotesSettings cfg = cfg();
        if (!cfg.enabled) {
            return;
        }
        PlayerNotesStore store = PlayerNotesStore.getInstance();
        if (store.book().size() == 0) {
            return;
        }
        learnUuids(minecraft, level, store);
        scanDungeonTeam(cfg, store);
        if (cfg.warnNearby) {
            scanNearby(minecraft, level, store);
        }
    }

    /**
     * Learns the account id of noted players standing in the world or listed in the tab, which is
     * what lets a note follow a rename. Cheap: players are few and the book is walked per player.
     */
    private static void learnUuids(Minecraft minecraft, ClientLevel level, PlayerNotesStore store) {
        for (Player player : level.players()) {
            if (player != minecraft.player && sbs.modid.client.core.player.RealPlayers.isRealPlayer(player)) {
                store.observe(player.getGameProfile().name(),
                        PlayerNoteBook.accountUuid(player.getUUID()));
            }
        }
        if (minecraft.getConnection() != null) {
            for (PlayerInfo info : minecraft.getConnection().getOnlinePlayers()) {
                if (PlayerLookup.isRealPlayer(info)) {
                    store.observe(info.getProfile().name(),
                            PlayerNoteBook.accountUuid(info.getProfile().id()));
                }
            }
        }
    }

    private void scanDungeonTeam(SBSConfig.PlayerNotesSettings cfg, PlayerNotesStore store) {
        Set<String> roster = DungeonTeamClasses.runRoster();   // lower-cased, empty outside a run
        if (roster.isEmpty()) {
            // Run over: release the teammates the party never knew about, so the next run with a
            // new team can warn about them again. Party members stay warned for the party.
            if (!dungeonOnly.isEmpty()) {
                partyWarned.removeAll(dungeonOnly);
                dungeonOnly.clear();
            }
            return;
        }
        if (!cfg.warnDungeonTeam) {
            return;
        }
        String self = PlayerLookup.selfName();
        for (String name : roster) {
            if (!PlayerNoteBook.isValidName(name) || name.equalsIgnoreCase(self)
                    || partyWarned.contains(name)) {
                continue;
            }
            PlayerNote note = store.book().match(name, PlayerLookup.uuidOf(name));
            if (note != null) {
                partyWarned.add(name);
                if (!PartyTracker.getInstance().isMember(name)) {
                    dungeonOnly.add(name);
                }
                warn(note, Source.DUNGEON);
            }
        }
    }

    private void scanNearby(Minecraft minecraft, ClientLevel level, PlayerNotesStore store) {
        for (Player player : level.players()) {
            if (player == minecraft.player) {
                continue;
            }
            String name = player.getGameProfile().name();
            if (!PlayerNoteBook.isValidName(name)) {
                continue;   // Hypixel NPCs are player entities with names like that
            }
            // Only a real player (in the player list) is ever matched - never an NPC, not even by a
            // name that happens to be noted.
            String uuid = PlayerNoteBook.accountUuid(player.getUUID());
            String key = PlayerNoteBook.key(name);
            if (uuid == null || !sbs.modid.client.core.player.RealPlayers.isRealPlayer(player) || nearbyWarned.contains(key) || partyWarned.contains(key)) {
                continue;
            }
            PlayerNote note = store.book().match(name, uuid);
            if (note != null) {
                nearbyWarned.add(key);
                warn(note, Source.NEARBY);
            }
        }
    }

    // ------------------------------------------------------------------ the warning

    private static void warn(PlayerNote note, Source source) {
        int mask = cfg().channels;
        if (AlertChannels.has(mask, AlertChannel.CHAT)) {
            SBSChat.send(line(note, source));
        }
        int rest = mask & ~AlertChannel.CHAT.bit();
        if (AlertChannels.any(rest)) {
            String title = note.name + " " + source.phrase;
            boolean showText = !note.note.isEmpty()
                    && !sbs.modid.client.helper.streamer.logic.StreamerNames.getInstance().active();
            String detail = note.tag.label() + (showText ? ": " + note.note : "");
            Alerts.send(Alerts.Alert.of(title, detail), rest);
        }
    }

    /**
     * {@code ⚠ Steve – AVOID: 'left at Maxor twice'  [NOTE] [KICK]}. The tag is written out, not
     * only coloured, so it reads without telling red from green.
     */
    static MutableComponent line(PlayerNote note, Source source) {
        String glyph = note.tag == NoteTag.AVOID ? "⚠ " : "● ";
        StringBuilder hover = new StringBuilder(note.name).append(' ').append(source.phrase);
        if (!note.formerNames.isEmpty()) {
            hover.append("\nPreviously: ").append(String.join(", ", note.formerNames));
        }
        MutableComponent body = Component.literal(" " + glyph).withColor(note.tag.color())
                .append(Component.literal(note.name).withColor(SBSChat.WHITE)
                        .withStyle(style -> style.withHoverEvent(
                                new HoverEvent.ShowText(Component.literal(hover.toString())))))
                .append(Component.literal(" – ").withColor(0xAAAAAA))
                .append(Component.literal(note.tag.name()).withColor(note.tag.color()));
        // Streamer Mode keeps the warning but not the text: a stream is the note leaving the machine.
        if (!note.note.isEmpty()
                && !sbs.modid.client.helper.streamer.logic.StreamerNames.getInstance().active()) {
            body.append(Component.literal(": '" + note.note + "'").withColor(0xDDDDDD));
        }
        // [NOTE] only fills the chat box: the player edits and sends it themselves, and the /sbs
        // command it contains never leaves the client.
        body.append(Component.literal("  "))
                .append(button("[NOTE]", SBSChat.PREFIX_COLOR,
                        new ClickEvent.SuggestCommand(PlayerNotesCommands.editCommand(note)),
                        "Edit this note: fills in the chat box, nothing is sent"));
        if (note.tag == NoteTag.AVOID && PartyTracker.getInstance().selfIsLeader()
                && PartyTracker.getInstance().isMember(note.name)
                && PlayerNoteBook.isValidName(note.name)) {
            body.append(Component.literal(" "))
                    .append(button("[KICK]", 0xFF5555,
                            new ClickEvent.RunCommand("/p kick " + note.name),
                            "Kick " + note.name + " from your party (/p kick " + note.name + ")"));
        }
        return body;
    }

    private static MutableComponent button(String label, int color, ClickEvent click, String hover) {
        return Component.literal(label).withColor(color)
                .withStyle(style -> style.withClickEvent(click)
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
    }
}
