/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import sbs.modid.client.combat.diana.model.MythCreature;
import sbs.modid.client.core.alert.Alerts;
import sbs.modid.client.core.audio.SbsAudio;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.util.ChatTag;
import sbs.modid.client.core.util.PlainText;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Telling the party where a rare creature is, and believing them when they tell you.
 *
 * <h2>The wire format is a convention, not a game message</h2>
 *
 * <p>Coordinates followed by a creature's name is a shape several clients agree on, and it is the
 * one thing in this whole feature that is not a fact about Hypixel. That has two consequences worth
 * stating: it can change without anything in the game changing, and a line that fails to parse is
 * somebody typing rather than the server rewording. Both mean the parser should be forgiving about
 * separators and strict about nothing else.
 *
 * <p>The mod already owns both halves of this shape elsewhere - {@code social/sendcoords/SendCoords}
 * writes it and {@code social/chat/logic/ChatWaypoints} reads it into generic markers. This class
 * exists for the part those cannot do: attaching a <i>creature</i> to the coordinates, so the marker
 * is coloured, named, alerted on and expires like a creature rather than like a pin.
 *
 * <h2>Sending is opt-in, receiving is not</h2>
 *
 * <p>Receiving is passive - somebody else chose to speak, and turning their message into a marker
 * costs nobody anything. Sending puts words in the player's mouth in front of other people, so it is
 * off until they switch it on, and it fires once per creature. The carry counter's party line set
 * that precedent and this follows it exactly.
 *
 * <h2>Guild chat is excluded</h2>
 *
 * <p>Deliberately. A guild is hundreds of people across every island, and a creature marker is only
 * meaningful to someone standing in the same Hub. Party and coop are the channels where the
 * coordinates mean something.
 */
public final class RareCreatureShare {

    private static final RareCreatureShare INSTANCE = new RareCreatureShare();

    /**
     * A chat line carrying coordinates and, optionally, something after them.
     *
     * <p>Separators are deliberately loose: people and clients write {@code x: 1, y: 2, z: 3},
     * {@code x:1 y:2 z:3} and every mixture of the two.
     */
    private static final Pattern SHARED = Pattern.compile(
            "(?i)^(?<who>[^:]{1,64}?)\\s*:\\s*x:\\s*(?<x>-?\\d+)[ ,]+y:\\s*(?<y>-?\\d+)[ ,]+"
                    + "z:\\s*(?<z>-?\\d+)\\s*(?<tail>.*)$");

    /** Channel prefixes we refuse: a guild is not standing in this Hub. */
    private static final String[] EXCLUDED_CHANNELS = {"guild >", "g >", "officer >", "o >"};

    private RareCreatureShare() {
    }

    public static RareCreatureShare getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.DianaSettings cfg() {
        return ConfigManager.getInstance().get().diana;
    }

    /**
     * One chat line. Never cancels: a message that turns into a marker is still a message the party
     * meant the player to read.
     */
    public void onChat(String rawText) {
        SBSConfig.DianaSettings cfg = cfg();
        if (!cfg.enabled || !cfg.receiveSharedCreatures || rawText == null) {
            return;
        }
        String text = PlainText.strip(rawText).trim();
        if (text.isEmpty()) {
            return;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String excluded : EXCLUDED_CHANNELS) {
            if (lower.startsWith(excluded)) {
                return;
            }
        }
        Matcher matcher = SHARED.matcher(text);
        if (!matcher.matches()) {
            return;
        }
        String tail = matcher.group("tail").replace("|", " ").trim();
        if (tail.isEmpty()) {
            // Bare coordinates with no creature named. Somebody sharing a spot, which
            // ChatWaypoints already turns into a generic marker - not our business.
            return;
        }
        MythCreature creature = creatureNamedIn(tail);
        if (creature == null || !MythMobTracker.watched(creature)) {
            return;
        }
        if (!SkyBlockLocation.onIsland("Hub")) {
            return;
        }

        BlockPos pos;
        try {
            pos = new BlockPos(Integer.parseInt(matcher.group("x")),
                    Integer.parseInt(matcher.group("y")),
                    Integer.parseInt(matcher.group("z")));
        } catch (NumberFormatException e) {
            return;
        }
        String sender = senderOf(matcher.group("who"));
        MythMobTracker.getInstance().addShared(creature, pos, sender);
        Alerts.send(new Alerts.Alert(creature.defaultName(),
                sender.isEmpty() ? "shared in party" : "shared by " + sender,
                SbsAudio.Tone.ALARM, null), cfg.creatureAlertChannels);
    }

    /**
     * The creature somebody named after their coordinates, or {@code null}.
     *
     * <p>Matched against the short forms as well as the full names, because "inq" is what people
     * type and a parser that insists on "Minos Inquisitor" reads almost nothing.
     */
    private static MythCreature creatureNamedIn(String tail) {
        SBSConfig.DianaSettings cfg = cfg();
        String cleaned = tail.trim().toLowerCase(Locale.ROOT);
        for (MythCreature creature : MythCreature.values()) {
            if (creature.namedBy(cleaned, cfg.creatureNames.get(creature.name()))) {
                return creature;
            }
        }
        return null;
    }

    /** The player name out of a channel-prefixed chat line, best effort. */
    private static String senderOf(String who) {
        String cleaned = who == null ? "" : who.trim();
        int arrow = cleaned.lastIndexOf('>');
        if (arrow >= 0 && arrow + 1 < cleaned.length()) {
            cleaned = cleaned.substring(arrow + 1).trim();
        }
        int space = cleaned.lastIndexOf(' ');
        if (space >= 0 && space + 1 < cleaned.length()) {
            // Ranks sit in front of the name: "[MVP+] Someone" -> "Someone".
            cleaned = cleaned.substring(space + 1);
        }
        return cleaned.replaceAll("[^A-Za-z0-9_]", "");
    }

    /**
     * Posts a creature's position to party chat.
     *
     * <p>The y value is the block the player is standing on rather than their eye position, which is
     * what makes the marker land on the ground for whoever reads it. Single-shot per creature, and
     * only ever after the player switched this on.
     */
    public void announce(MythMobTracker.Sighting sighting, Player player) {
        SBSConfig.DianaSettings cfg = cfg();
        if (!cfg.enabled || !cfg.announceSpawnsToParty || sighting == null || player == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.player.connection == null) {
            return;
        }
        String note = cfg.announceFormat == null || cfg.announceFormat.isBlank()
                ? sighting.creature().defaultName()
                : cfg.announceFormat.replace("{creature}", sighting.creature().defaultName());
        String message = String.format(Locale.ROOT, "x: %d, y: %d, z: %d | %s",
                Math.round(player.getX()), Math.round(player.getY()) - 1, Math.round(player.getZ()),
                note.trim());
        try {
            minecraft.player.connection.sendCommand("pc " + ChatTag.tag(message));
        } catch (RuntimeException e) {
            // Not connected, or the server refused it. An announcement is best-effort and never
            // retried: a retry loop is a bot however small each step is.
            DianaDebug.getInstance().note("party announcement not sent: " + e.getMessage());
        }
    }
}
