/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.hideplayers.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.PlayerTeam;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.social.chat.logic.SBSChat;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.tab.TabWidgets;
import sbs.modid.client.dungeons.run.model.DungeonTeamClasses;
import sbs.modid.client.helper.hideplayers.model.HidingRule;
import sbs.modid.client.helper.hideplayers.model.SkyBlockLevelText;
import sbs.modid.client.social.notes.logic.PlayerNotesStore;
import sbs.modid.client.social.notes.model.NoteTag;
import sbs.modid.client.social.party.logic.PartyTracker;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Hide Low-Level Players: the SkyBlock level of the players around you, and the chat half of the
 * feature.
 *
 * <p><b>In memory only.</b> Name to level, learned from the tab list (the team prefix "[212] ") and
 * from the "[212]" in front of public chat lines. Never written to disk, never shared, never sent.
 * Cleared when the level (world or server) changes; the next tab read refills whoever is still
 * there. A player whose level is not known is never hidden.
 *
 * <p>Client thread only: filled in {@link #tick} and in the chat funnel, read by
 * {@link PlayerHiding#tick}. Nothing here runs while the feature is off.
 */
public final class PlayerLevels {

    /** Ticks between tab-list reads (team prefixes change without a TabWidgets revision). */
    private static final int TAB_READ_INTERVAL = 20;

    private static final Map<String, Integer> byName = new HashMap<>();
    private static ClientLevel seenLevel;
    private static int ticksUntilRead;
    private static boolean loggedThisWorld;

    /** Shortest gap between two "N messages hidden" lines. */
    private static final long NOTICE_INTERVAL_MS = 60_000;
    /** Lines hidden since the last notice. A count only - no content, no names. */
    private static int hiddenSinceNotice;
    private static long lastNoticeAt;

    private PlayerLevels() {
    }

    private static SBSConfig.HidePlayersSettings cfg() {
        return ConfigManager.getInstance().get().hidePlayers;
    }

    /** Game tick: resets on a level change and re-reads the tab list once a second. */
    public static void tick(Minecraft minecraft) {
        if (!cfg().lowLevelEnabled) {
            if (!byName.isEmpty()) {
                byName.clear();
                tabDecisions.clear();
            }
            seenLevel = null;
            return;
        }
        if (minecraft.level != seenLevel) {
            seenLevel = minecraft.level;
            byName.clear();
            ticksUntilRead = 0;
            loggedThisWorld = false;
        }
        if (--ticksUntilRead > 0) {
            return;
        }
        ticksUntilRead = TAB_READ_INTERVAL;
        tabDecisions.clear();
        sendNotice(minecraft);
        ClientPacketListener connection = minecraft.getConnection();
        if (connection == null) {
            return;
        }
        int players = 0;
        int withLevel = 0;
        for (PlayerInfo info : connection.getOnlinePlayers()) {
            if (!TabWidgets.isPlayer(info) || info.getProfile() == null) {
                continue;
            }
            players++;
            PlayerTeam team = info.getTeam();
            if (team == null) {
                continue;
            }
            int level = SkyBlockLevelText.prefixLevel(team.getPlayerPrefix().getString());
            if (level >= 0) {
                withLevel++;
                byName.put(info.getProfile().name().toLowerCase(Locale.ROOT), level);
            }
        }
        if (!loggedThisWorld && players > 0) {
            // Once per world: the evidence that the team-prefix read works in this lobby type. Counts only.
            loggedThisWorld = true;
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][HidePlayers] Tab level read: {} players, {} with a level.",
                    players, withLevel);
        }
    }

    /** The optional "N messages hidden" line: at most once per {@link #NOTICE_INTERVAL_MS}, counts only. */
    private static void sendNotice(Minecraft minecraft) {
        if (hiddenSinceNotice == 0 || !cfg().lowLevelNotice || minecraft.player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastNoticeAt < NOTICE_INTERVAL_MS) {
            return;
        }
        lastNoticeAt = now;
        int count = hiddenSinceNotice;
        hiddenSinceNotice = 0;
        SBSChat.send(Component.literal(" " + count + (count == 1 ? " message" : " messages")
                + " from low-level players hidden").withColor(SBSChat.WHITE));
    }

    /** The known level of a player, or {@link HidingRule#UNKNOWN_LEVEL}. */
    public static int level(String name) {
        Integer level = byName.get(name.toLowerCase(Locale.ROOT));
        return level == null ? HidingRule.UNKNOWN_LEVEL : level;
    }

    /**
     * Chat funnel (BetterChat, below the parsers): learns the speaker's level from a public line and
     * says whether to hide it. Your own lines, system lines and party/guild/private/co-op chat never
     * match the public shape, so they are never hidden.
     */
    public static boolean shouldHideChat(Component content) {
        SBSConfig.HidePlayersSettings cfg = cfg();
        if (!cfg.lowLevelEnabled) {
            return false;
        }
        SkyBlockLevelText.Speaker speaker = SkyBlockLevelText.publicSpeaker(content.getString());
        if (speaker == null) {
            return false;
        }
        byName.put(speaker.name().toLowerCase(Locale.ROOT), speaker.level());
        if (!cfg.lowLevelChat) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return false;
        }
        boolean hide = mayHideBelow(minecraft, cfg, speaker.name(), speaker.level());
        logChatOnce(speaker, hide, cfg.minLevel);
        if (!hide) {
            return false;
        }
        hiddenSinceNotice++;
        return true;
    }

    /**
     * Whether a tab-list row shows a player below the level - left out of the SBS tab list (asked
     * 2026-10-17). A real player entry is judged by its profile name and the level learnt from its
     * team prefix; a text row ("[445] ⚡ [MVP++] XJU", how SkyBlock lists players) by its own text.
     * Same exceptions as chat and the world; an unknown level is never hidden.
     */
    public static boolean shouldHideInTab(String rowText, String profileName, boolean playerEntry) {
        SBSConfig.HidePlayersSettings cfg = cfg();
        Minecraft minecraft = Minecraft.getInstance();
        if (!cfg.lowLevelEnabled || !cfg.lowLevelTab || rowText == null || minecraft.player == null) {
            return false;
        }
        // Asked per row per frame while the tab is held: decided once per row per tab read.
        String key = playerEntry && profileName != null ? "p:" + profileName : rowText;
        return tabDecisions.computeIfAbsent(key, k -> {
            if (playerEntry && profileName != null) {
                return mayHideBelow(minecraft, cfg, profileName, level(profileName));
            }
            SkyBlockLevelText.Speaker row = SkyBlockLevelText.tabPlayer(rowText);
            return row != null && mayHideBelow(minecraft, cfg, row.name(), row.level());
        });
    }

    /** Tab-list decisions since the last tab read; cleared with each read in {@link #tick}. */
    private static final Map<String, Boolean> tabDecisions = new HashMap<>();

    /** The one decision chat and tab share: below the level and not one of the kept players. */
    private static boolean mayHideBelow(Minecraft minecraft, SBSConfig.HidePlayersSettings cfg, String name, int level) {
        boolean self = name.equalsIgnoreCase(minecraft.player.getGameProfile().name());
        HidingRule.Candidate candidate = new HidingRule.Candidate(self, true,
                cfg.keepParty && PartyTracker.getInstance().isMember(name),
                cfg.keepDungeonTeam && dungeonTeam(cfg).contains(name.toLowerCase(Locale.ROOT)),
                cfg.keepTrusted && trusted(minecraft, name),
                0);
        return HidingRule.mayHideBelowLevel(candidate, settings(cfg), level, cfg.minLevel, false);
    }

    /** Public lines read and hidden since the last diagnostic line; counts and the last kept speaker only. */
    private static int chatRead;
    private static int chatHidden;
    private static String lastKept = "-";
    private static long lastChatLog;

    /**
     * At most once a minute, what the chat half did: how many public lines it read, how many it hid,
     * and the last speaker below the level it let through. The 2026-10-17 hub log showed lines from
     * levels 201..445 with the minimum at 500, none hidden, while the same lines parse right in a
     * test - this line tells which step lets them through. Names below the level only, no text.
     */
    private static void logChatOnce(SkyBlockLevelText.Speaker speaker, boolean hidden, int minLevel) {
        chatRead++;
        if (hidden) {
            chatHidden++;
        } else if (speaker.level() < minLevel) {
            lastKept = speaker.name() + " [" + speaker.level() + "] party=" + PartyTracker.getInstance().isMember(speaker.name());
        }
        long now = System.currentTimeMillis();
        if (now - lastChatLog < NOTICE_INTERVAL_MS) {
            return;
        }
        lastChatLog = now;
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][HidePlayers] chat: {} public line(s) read, {} hidden (below {}); "
                + "last kept below: {}", chatRead, chatHidden, minLevel, lastKept);
        chatRead = 0;
        chatHidden = 0;
        lastKept = "-";
    }

    static HidingRule.Settings settings(SBSConfig.HidePlayersSettings cfg) {
        return new HidingRule.Settings(cfg.everywhere, cfg.radius, cfg.keepParty, cfg.keepDungeonTeam,
                cfg.keepTrusted, cfg.nearMe, cfg.nearNpcs, cfg.npcRadius);
    }

    private static Set<String> dungeonTeam(SBSConfig.HidePlayersSettings cfg) {
        return cfg.keepDungeonTeam ? DungeonTeamClasses.runRoster() : Set.of();
    }

    /** Trusted needs the account id; a speaker not in the player list falls back to a name-only note. */
    private static boolean trusted(Minecraft minecraft, String name) {
        ClientPacketListener connection = minecraft.getConnection();
        PlayerInfo info = connection == null ? null : connection.getPlayerInfo(name);
        java.util.UUID uuid = info != null && info.getProfile() != null ? info.getProfile().id() : null;
        return PlayerNotesStore.getInstance().markerTag(uuid, name) == NoteTag.TRUSTED;
    }
}
