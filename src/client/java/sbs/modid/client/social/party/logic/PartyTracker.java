/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.party.logic;

import net.minecraft.client.Minecraft;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig.PartyHighlightSettings;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tracks who is in your Hypixel party by reading the party chat notifications, so the Party highlight can
 * highlight those members among the players on the island.
 *
 * <p>Hypixel never sends a machine-readable party roster, so the list is built from the human
 * messages: "{@code [MVP+] Name joined the party.}", "{@code Name has left the party.}", the kick /
 * disconnect / disband lines, and the "Party Members:" roster printed on join or {@code /pl}. A rank
 * prefix ({@code [MVP+]}) is optional and stripped to the bare IGN.
 *
 * <p><b>Staleness.</b> Hypixel removes a member ~5 minutes after they go offline, so the roster is
 * persisted with a timestamp and discarded on launch when it is older than that – you would have been
 * kicked while the game was closed. The timestamp is refreshed on every party event and whenever a
 * member is actually seen in the world, so a live party never expires. A manual reset is provided too.
 */
public final class PartyTracker {

    private static final PartyTracker INSTANCE = new PartyTracker();

    /** Hypixel kicks an offline member after ~5 minutes; a roster older than this on load is stale. */
    private static final long STALE_MS = 5 * 60_000L;
    /** Throttle for the "seen a member -> refresh timestamp" save. */
    private static final long SEEN_SAVE_MS = 30_000L;

    private static final String RANK = "(?:\\[[^\\]]+\\] )?";
    private static final String NAME = "([A-Za-z0-9_]{1,16})";

    private static final Pattern JOINED = Pattern.compile("^" + RANK + NAME + " joined the party\\.");
    private static final Pattern LEFT = Pattern.compile("^" + RANK + NAME + " has left the party\\.");
    private static final Pattern REMOVED = Pattern.compile(
            "^" + RANK + NAME + " has been removed from (?:the|your) party");
    private static final Pattern DISCONNECT = Pattern.compile(
            "^" + RANK + NAME + " was removed from your party because they disconnected");
    private static final Pattern DISBANDED = Pattern.compile("^" + RANK + NAME + " has disbanded the party");
    private static final Pattern JOINED_THEIRS = Pattern.compile(
            "^You have joined " + RANK + NAME + "'s party!");
    /** Strips a "[MVP+]" style rank so only names + separators remain in a roster line. */
    private static final Pattern RANK_TAG = Pattern.compile("\\[[^\\]]+\\]");

    /** "Party Leader: [MVP+] Name ●" from /pl - the one line that names the leader outright. */
    private static final Pattern LEADER_LINE = Pattern.compile("^Party Leader: " + RANK + NAME);
    /**
     * A leadership change. Wording not yet seen in a capture, so both forms are tolerant and a miss
     * only costs the [KICK] button its accuracy until the next /pl - never a wrong kick, since the
     * player still has to click it and Hypixel refuses a kick from a non-leader.
     */
    private static final Pattern TRANSFERRED = Pattern.compile(
            "^The party was transferred to " + RANK + NAME);
    private static final Pattern PROMOTED_LEADER = Pattern.compile(
            "^" + RANK + "[A-Za-z0-9_]{1,16} has promoted " + RANK + NAME + " to Party Leader");

    private long lastSeenSave;
    /**
     * Who leads the party, when a chat line has said so; {@code null} when unknown. Memory only:
     * after a restart it is unknown until the next /pl, and unknown means "not you".
     */
    private volatile String leader;

    private PartyTracker() {
    }

    public static PartyTracker getInstance() {
        return INSTANCE;
    }

    private static PartyHighlightSettings cfg() {
        return ConfigManager.getInstance().get().partyHighlight;
    }

    // ------------------------------------------------------------------ chat parsing

    /** Called with every displayed chat line (color codes already stripped by the caller). */
    public void parseChat(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        // Whole-party clears.
        if (DISBANDED.matcher(text).find()
                || text.startsWith("You left the party")
                || text.startsWith("You have left the party")
                || text.startsWith("You are not currently in a party")
                || text.startsWith("You have been kicked from the party")
                || text.startsWith("The party was disbanded")) {
            clear();
            return;
        }
        Matcher joined = JOINED.matcher(text);
        if (joined.find()) {
            // Someone joining a party we were not in means we invited them, so it is ours.
            if (cfg().members.isEmpty() && leader == null) {
                leader = selfName();
            }
            add(joined.group(1));
            return;
        }
        Matcher joinedTheirs = JOINED_THEIRS.matcher(text);
        if (joinedTheirs.find()) {
            leader = joinedTheirs.group(1);
            add(joinedTheirs.group(1));
            return;
        }
        Matcher transferred = TRANSFERRED.matcher(text);
        if (transferred.find()) {
            leader = transferred.group(1);
            return;
        }
        Matcher promoted = PROMOTED_LEADER.matcher(text);
        if (promoted.find()) {
            leader = promoted.group(1);
            return;
        }
        Matcher left = LEFT.matcher(text);
        if (left.find()) {
            remove(left.group(1));
            return;
        }
        Matcher removed = REMOVED.matcher(text);
        if (removed.find()) {
            remove(removed.group(1));
            return;
        }
        Matcher disconnect = DISCONNECT.matcher(text);
        if (disconnect.find()) {
            remove(disconnect.group(1));
            return;
        }
        // Roster lines from /pl or the join summary: additive (removals come from the leave lines).
        // Strip the ranks, then split on every non-name char (spaces + the ●/○ status dots, whatever
        // exact glyph Hypixel uses) and keep the Minecraft-name-shaped tokens.
        if (text.startsWith("Party Leader:") || text.startsWith("Party Moderators:")
                || text.startsWith("Party Members:")) {
            Matcher leaderLine = LEADER_LINE.matcher(text);
            if (leaderLine.find()) {
                leader = leaderLine.group(1);
            }
            String rest = RANK_TAG.matcher(text.substring(text.indexOf(':') + 1)).replaceAll(" ");
            boolean any = false;
            for (String token : rest.split("[^A-Za-z0-9_]+")) {
                if (token.length() >= 3 && token.length() <= 16) {
                    any |= addSilently(token);
                }
            }
            if (any) {
                touch();
            }
        }
    }

    // ------------------------------------------------------------------ membership

    private void add(String name) {
        if (addSilently(name)) {
            touch();
        }
    }

    private boolean addSilently(String name) {
        if (name == null || name.isBlank() || name.equalsIgnoreCase(selfName())) {
            return false;
        }
        List<String> members = cfg().members;
        for (String existing : members) {
            if (existing.equalsIgnoreCase(name)) {
                return false;
            }
        }
        members.add(name);
        // Every name newly entering the roster, from a join line or a roster line alike.
        sbs.modid.client.social.notes.logic.PlayerNotesWarner.getInstance().onPartyMember(name);
        return true;
    }

    private void remove(String name) {
        if (cfg().members.removeIf(existing -> existing.equalsIgnoreCase(name))) {
            touch();
        }
    }

    private void clear() {
        leader = null;
        sbs.modid.client.social.notes.logic.PlayerNotesWarner.getInstance().onPartyEnded();
        if (!cfg().members.isEmpty()) {
            cfg().members.clear();
            touch();
            // The party ending is the one moment a party send-channel becomes dangerous rather than
            // merely wrong: the next message would go to a lobby. This is the only place the mod can
            // observe that transition, so the notification belongs here and not on a timer.
            sbs.modid.client.social.chat.logic.ActiveChannel.getInstance().onPartyLost();
        }
    }

    /**
     * Whether a chat line has named the local player as party leader. {@code false} when unknown,
     * which is the safe answer: it only decides whether a [KICK] button is offered at all.
     */
    public boolean selfIsLeader() {
        String current = leader;
        return current != null && !current.isEmpty() && current.equalsIgnoreCase(selfName());
    }

    /** Manual reset (settings button). */
    public void reset() {
        leader = null;
        sbs.modid.client.social.notes.logic.PlayerNotesWarner.getInstance().onPartyEnded();
        cfg().members.clear();
        cfg().updatedAt = 0;
        ConfigManager.getInstance().save();
    }

    /** True when {@code name} is a tracked party member (case-insensitive). */
    public boolean isMember(String name) {
        if (name == null) {
            return false;
        }
        for (String member : cfg().members) {
            if (member.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /** The current roster (case-preserving snapshot). */
    public Set<String> members() {
        return new LinkedHashSet<>(cfg().members);
    }

    public boolean hasParty() {
        return !cfg().members.isEmpty();
    }

    // ------------------------------------------------------------------ lifecycle

    /** Called once at client init: drop a roster that is older than the offline-kick window. */
    public void clearIfStale() {
        PartyHighlightSettings settings = cfg();
        if (!settings.members.isEmpty() && System.currentTimeMillis() - settings.updatedAt >= STALE_MS) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Party] roster stale ({} members, {}s old) - cleared",
                    settings.members.size(), (System.currentTimeMillis() - settings.updatedAt) / 1000);
            settings.members.clear();
            settings.updatedAt = 0;
            ConfigManager.getInstance().save();
        }
    }

    /** Refreshes the timestamp when a member is actually seen in the world (throttled save). */
    public void noteSeen() {
        long now = System.currentTimeMillis();
        cfg().updatedAt = now;
        if (now - lastSeenSave > SEEN_SAVE_MS) {
            lastSeenSave = now;
            ConfigManager.getInstance().save();
        }
    }

    private void touch() {
        cfg().updatedAt = System.currentTimeMillis();
        ConfigManager.getInstance().save();
    }

    private static String selfName() {
        var player = Minecraft.getInstance().player;
        return player == null ? "" : player.getGameProfile().name();
    }
}
