/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.chat.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig.ChatTabsSettings;
import sbs.modid.client.core.mixin.ChatComponentAccessor;
import sbs.modid.client.social.chat.model.ChatChannel;
import sbs.modid.client.social.chat.model.ChatTab;

import java.util.ArrayList;
import java.util.List;

/**
 * Chat Tabs: which channel the chat is currently showing, and what that hides.
 *
 * <p><b>Nothing is thrown away.</b> A message the active tab does not show is still received,
 * still parsed by every tracker and still stored in the chat's own history — only the
 * <i>display</i> list is filtered, from a mixin on {@code ChatComponent#addMessageToDisplayQueue}.
 * Switching tab therefore re-runs vanilla's {@code refreshTrimmedMessages} over the messages that
 * were there all along, and the ones the previous tab hid come back. A filter that dropped messages
 * at arrival would make every tab switch lossy, which is the one thing a chat filter must not be.
 *
 * <p>Two lines are never hidden by a tab unless you say so: the server's own messages
 * ({@link ChatTabsSettings#keepSystem}, off by default, because "only party chat" should mean only
 * party chat) and this mod's own {@code [SBS]} lines ({@link ChatTabsSettings#keepModMessages}, on
 * by default — an alert this mod raised should not be swallowed by this mod's own filter).
 *
 * <p>The active tab is not persisted. It resets to the configured default whenever the chat history
 * is cleared — a rejoin, a server hop — so nobody starts a session with a chat that is silently
 * filtered by a choice they made yesterday.
 */
public final class ChatTabs {

    private static final ChatTabs INSTANCE = new ChatTabs();

    /** Anything past this is drawn as "9+"; the exact count stops mattering long before. */
    public static final int UNREAD_CAP = 9;

    private volatile ChatTab active = ChatTab.ALL;

    /** Messages that arrived for a tab while another one was showing, by {@link ChatTab#ordinal()}. */
    private final int[] unread = new int[ChatTab.values().length];

    private ChatTabs() {
    }

    public static ChatTabs getInstance() {
        return INSTANCE;
    }

    private static ChatTabsSettings cfg() {
        return ConfigManager.getInstance().get().chatTabs;
    }

    /** Whether the filter is doing anything at all right now. */
    public boolean enabled() {
        return cfg().enabled;
    }

    /** The tab currently showing. Always {@link ChatTab#ALL} while the module is off. */
    public ChatTab active() {
        return cfg().enabled ? active : ChatTab.ALL;
    }

    /** The tabs on offer: {@link ChatTab#ALL} plus the channels switched on in the settings. */
    public List<ChatTab> tabs() {
        ChatTabsSettings cfg = cfg();
        List<ChatTab> out = new ArrayList<>(ChatTab.values().length);
        out.add(ChatTab.ALL);
        if (cfg.tabPublic) {
            out.add(ChatTab.PUBLIC);
        }
        if (cfg.tabParty) {
            out.add(ChatTab.PARTY);
        }
        if (cfg.tabGuild) {
            out.add(ChatTab.GUILD);
        }
        if (cfg.tabCoop) {
            out.add(ChatTab.COOP);
        }
        if (cfg.tabPrivate) {
            out.add(ChatTab.PRIVATE);
        }
        // The IRC tab follows IRC Chat itself: a tab for a channel that is switched off would only
        // ever be an empty view, and one the player cannot fix from this page.
        if (cfg.tabIrc && ConfigManager.getInstance().get().chatOptions.ircEnabled) {
            out.add(ChatTab.IRC);
        }
        return out;
    }

    /** Whether the IRC tab can be offered at all — {@code false} while IRC Chat is switched off. */
    public static boolean ircAvailable() {
        return ConfigManager.getInstance().get().chatOptions.ircEnabled;
    }

    /** How many messages arrived for {@code tab} while another one was showing. */
    public int unread(ChatTab tab) {
        return tab == null ? 0 : unread[tab.ordinal()];
    }

    // ------------------------------------------------------------------ the filter

    /**
     * Whether {@code content} belongs on the tab that is showing. The single decision point: the
     * display filter, the unread counters and the settings preview all ask this, so what a tab shows
     * and what it counts can never drift apart.
     */
    public boolean isVisible(Component content) {
        if (!cfg().enabled || active == ChatTab.ALL) {
            return true;
        }
        return shows(active, ChatChannel.of(content == null ? null : content.getString()));
    }

    /** Whether a message on {@code channel} is drawn while {@code tab} is showing. */
    private boolean shows(ChatTab tab, ChatChannel channel) {
        // A channel's own tab is where you go to read it, muted or not - a tab that is always empty
        // would be a worse answer than no tab.
        if (tab != ChatTab.ALL && tab.accepts(channel)) {
            return true;
        }
        if (isMuted(channel)) {
            return false;
        }
        if (tab == ChatTab.ALL) {
            return true;
        }
        // Some other channel, on a tab that is not its own.
        ChatTabsSettings cfg = cfg();
        if (channel == ChatChannel.MOD) {
            return cfg.keepModMessages;
        }
        if (channel == ChatChannel.SYSTEM) {
            return cfg.keepSystem;
        }
        return false;
    }

    /**
     * Whether a channel is muted out of the All tab.
     *
     * <p><b>Muting only counts while the channel has a tab of its own.</b> Otherwise switching a tab
     * off and muting the same channel - two reasonable-looking clicks, on the same page - would put
     * its messages somewhere the player cannot reach at all, which is the one thing this feature
     * promises never to do. With the tab gone the mute is simply inert, and its settings row says so.
     */
    private boolean isMuted(ChatChannel channel) {
        ChatTabsSettings cfg = cfg();
        boolean muted = switch (channel) {
            case PUBLIC -> cfg.mutePublic;
            case PARTY -> cfg.muteParty;
            case GUILD, OFFICER -> cfg.muteGuild;
            case COOP -> cfg.muteCoop;
            case PRIVATE -> cfg.mutePrivate;
            case IRC -> cfg.muteIrc;
            case SYSTEM, MOD -> false;
        };
        return muted && tabs().stream().anyMatch(tab -> tab != ChatTab.ALL && tab.accepts(channel));
    }

    /**
     * One message has just been stored. Bumps the counter of every other tab that would have shown
     * it, so a tab is never a place messages disappear into unnoticed.
     *
     * <p>Called from the "message added to the history" hook rather than from the display filter:
     * the display filter also runs on every rebuild (a tab switch, a window resize), and counting
     * there would inflate the badges every time the chat is redrawn.
     */
    public void onMessageStored(Component content) {
        ChatTabsSettings cfg = cfg();
        if (!cfg.enabled || active == ChatTab.ALL) {
            return;   // nothing is hidden, so nothing is unread
        }
        ChatChannel channel = ChatChannel.of(content == null ? null : content.getString());
        if (shows(active, channel)) {
            return;   // the player is looking at it
        }
        for (ChatTab tab : tabs()) {
            if (tab != active && shows(tab, channel) && unread[tab.ordinal()] < Integer.MAX_VALUE) {
                unread[tab.ordinal()]++;
            }
        }
    }

    // ------------------------------------------------------------------ switching

    /** Shows {@code tab}, clears its unread count and redraws the chat from the stored history. */
    public void select(ChatTab tab) {
        if (tab == null) {
            return;
        }
        active = tab;
        unread[tab.ordinal()] = 0;
        // A tab is a view, so this is the one place the mod turns "what I am reading" into "where I
        // am sending" - and SendChannel.from is deliberately conservative about the tabs that name
        // no destination. Opt-in twice over: the feature is off by default and so is following tabs.
        if (ConfigManager.getInstance().get().channelSend.followTabs) {
            ActiveChannel.getInstance().set(
                    sbs.modid.client.social.chat.model.SendChannel.from(tab));
        }
        refresh();
    }

    /** Steps to the next tab on offer, wrapping. Bound to the hotkey; unbound by default. */
    public void next() {
        List<ChatTab> tabs = tabs();
        if (tabs.size() < 2) {
            return;
        }
        int index = tabs.indexOf(active());
        select(tabs.get((index + 1) % tabs.size()));
    }

    /** The hotkey that steps to the next tab (from {@code CommandKeyMixin}). */
    public void onKeyPressed(int key) {
        ChatTabsSettings cfg = cfg();
        if (!cfg.enabled || cfg.cycleKey == 0 || key != cfg.cycleKey) {
            return;
        }
        next();
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.sendOverlayMessage(Component.literal("§bChat: §f" + active().label()));
        }
    }

    /**
     * Back to the configured default tab, with every counter cleared. Called when the chat history
     * is cleared, which is the moment the messages a tab was hiding stop existing.
     */
    public void reset() {
        ChatTab wanted = cfg().defaultTab;
        active = wanted != null && tabs().contains(wanted) ? wanted : ChatTab.ALL;
        java.util.Arrays.fill(unread, 0);
    }

    /**
     * Rebuilds what is drawn from the messages that are stored, and scrolls back to the newest line
     * — the scroll position belongs to the list that was showing a moment ago and means nothing in
     * the new one.
     *
     * <p>Also the repair path for a settings change: turning the module off, or hiding the tab that
     * was showing, has to put the hidden messages back immediately rather than at the next message.
     */
    public void refresh() {
        ChatTabsSettings cfg = cfg();
        if (!cfg.enabled || !tabs().contains(active)) {
            active = ChatTab.ALL;
        }
        ChatComponent chat = ChatAccess.get();
        if (chat == null) {
            return;   // nothing has been displayed yet, so there is nothing to rebuild
        }
        ((ChatComponentAccessor) chat).skyblockSimplified$refreshTrimmedMessages();
        chat.resetChatScroll();
    }
}
