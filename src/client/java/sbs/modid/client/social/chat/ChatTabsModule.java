/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.chat;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.social.chat.logic.ActiveChannel;
import sbs.modid.client.social.chat.logic.ChatTabs;
import sbs.modid.client.social.chat.model.ChatTab;
import sbs.modid.client.social.chat.model.SendChannel;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.ui.HudEditorScreen;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;

/**
 * Chat Tabs module (Party &amp; Chat): sort the chat by channel and read one channel at a time.
 * Self-registered via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 *
 * <p>Every row that changes what a tab shows refreshes the chat immediately
 * ({@link ChatTabs#refresh()}) — a filter that only takes effect at the next message looks broken.
 */
public final class ChatTabsModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public ChatTabsModule() {
    }

    @Override
    public String id() {
        return "chat_tabs";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.PARTY_CHAT;
    }

    @Override
    public String displayName() {
        return "Chat Tabs";
    }

    @Override
    public String description() {
        return "Read one chat channel at a time - party, guild, DMs or all";
    }

    @Override
    public int accentColor() {
        return 0xFF3FB4FF;
    }

    private static SBSConfig.ChatTabsSettings cfg() {
        return ConfigManager.getInstance().get().chatTabs;
    }

    private static SBSConfig.ChannelSendSettings sendCfg() {
        return ConfigManager.getInstance().get().channelSend;
    }

    /** Saves without redrawing the chat: the send settings change where messages go, not what is shown. */
    private static void save() {
        ConfigManager.getInstance().save();
    }

    /** Saves, then puts the chat back in step with the setting that just changed. */
    private static void apply() {
        ConfigManager.getInstance().save();
        ChatTabs.getInstance().refresh();
    }

    private static void open(net.minecraft.client.gui.screens.Screen screen) {
        net.minecraft.client.Minecraft.getInstance().setScreenAndShow(screen);
    }

    /**
     * The IRC tab, greyed out while IRC Chat itself is off — with the reason on the row, because a
     * row that is dead for invisible reasons is a bug report waiting to happen (ui/AGENTS.md).
     *
     * <p>The greying follows IRC Chat live. It used to be decided when the row was built, so a row
     * built while IRC Chat was off stayed dead after it was switched on - on the Favourites page for
     * the whole visit - and the IRC tab could not be switched off from it.
     */
    static SettingRow ircTab() {
        return SettingRow.toggle("IRC Tab", () -> cfg().tabIrc,
                        () -> {
                            cfg().tabIrc = !cfg().tabIrc;
                            SettingRow.logChange("IRC Tab", cfg().tabIrc);
                            apply();
                        })
                .describe(ChatTab.IRC.description() + ". Only appears while IRC Chat is switched on, "
                        + "over on the Chat Options page; greyed out while it is off. Default: on.")
                .disabledWhile(() -> !ChatTabs.ircAvailable());
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Chat Tabs", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; apply(); })
                        .describe("Sorts your chat by channel and shows one channel at a time: "
                                + "party chat only, guild only, DMs only, or all of it. Nothing is "
                                + "deleted - a message another tab is holding is still there and "
                                + "comes back the moment you click that tab. Off by default."),
                SettingRow.label("Click a tab under the chat, or bind a key below"),

                SettingRow.label("§8Send To The Selected Channel"),
                SettingRow.toggle("Type Without The Command",
                        () -> sendCfg().enabled,
                        () -> { sendCfg().enabled = !sendCfg().enabled; save(); })
                        .describe("While a channel is selected, what you type goes to that channel "
                                + "without typing /pc or /gc first. The channel is shown on the chat "
                                + "box the whole time, so you can see where a message is going "
                                + "before you send it. Typing a / command still does exactly what "
                                + "you typed. Off by default."),
                SettingRow.enumOptions("Send Channel",
                        () -> ActiveChannel.getInstance().active(),
                        value -> ActiveChannel.getInstance().set(value),
                        SendChannel::label)
                        .describe("Where typed messages go right now. Public sends them exactly as "
                                + "you typed them, which is what happens with this feature off. "
                                + "Resets to Public when you change world or server, and when you "
                                + "leave the party if Party was selected."),
                SettingRow.toggle("Follow The Tabs", () -> sendCfg().followTabs,
                        () -> { sendCfg().followTabs = !sendCfg().followTabs; save(); })
                        .describe("Clicking a tab also sets where you send. The All and DM tabs are "
                                + "not destinations, so those select Public rather than guessing - a "
                                + "DM needs a name, and the last person to message you is not "
                                + "necessarily who you meant. On by default."),
                SettingRow.toggle("Colour The Chat Box", () -> sendCfg().colorInput,
                        () -> { sendCfg().colorInput = !sendCfg().colorInput; save(); })
                        .describe("Outlines the box you type in with the channel's colour. The "
                                + "channel's name is shown either way - colour is never the only "
                                + "thing saying where a message is going. On by default."),
                SettingRow.toggle("Keep The Channel After Restarting", () -> sendCfg().persist,
                        () -> { sendCfg().persist = !sendCfg().persist; save(); })
                        .describe("Remembers the selected channel across a restart. Off by default: "
                                + "a channel picked an hour ago, in a party that has since broken "
                                + "up, is the least predictable way for this to behave."),

                SettingRow.toggle("Show The Tab Strip", () -> cfg().showBar,
                        () -> { cfg().showBar = !cfg().showBar; apply(); })
                        .describe("Draws the row of tabs by the chat, each with the number of "
                                + "messages waiting on it. On by default; with it off you can still "
                                + "switch channel with the key at the bottom of this page."),
                SettingRow.toggle("Keep It Up While Not Typing", () -> cfg().barWhileClosed,
                        () -> { cfg().barWhileClosed = !cfg().barWhileClosed; apply(); })
                        .describe("Leaves the strip on screen when the chat is closed, so you can "
                                + "see what is waiting on the other channels while you play. Off "
                                + "shows it only while the chat is open. On by default."),
                SettingRow.button("Move / Resize The Tabs", () -> open(new HudEditorScreen(
                        new HudElement[] {HudElement.CHAT_TABS}, "Edit Chat Tabs")))
                        .describe("Opens the GUI editor with the tab strip in it: drag it anywhere "
                                + "on screen, scale it, set its opacity, or take it off the screen "
                                + "entirely with the minus button. It starts just above the place "
                                + "you type."),
                SettingRow.label("Drag, scale or remove the strip like any other HUD element"),

                SettingRow.enumOptions("Default Tab", () -> {
                            ChatTab tab = cfg().defaultTab;
                            return tab == null ? ChatTab.ALL : tab;
                        },
                        value -> { cfg().defaultTab = value; apply(); }, ChatTab::label)
                        .describe("Which tab you start on after a rejoin or a server hop. The tab "
                                + "you pick while playing is never remembered past that point, so "
                                + "you can never log in to a chat that is quietly filtered. "
                                + "Default: All."),

                SettingRow.label("Tabs on offer (a tab that is off is not selectable):"),
                SettingRow.toggle("Public Tab", () -> cfg().tabPublic,
                        () -> { cfg().tabPublic = !cfg().tabPublic; apply(); })
                        .describe(ChatTab.PUBLIC.description() + ". On by default."),
                SettingRow.toggle("Party Tab", () -> cfg().tabParty,
                        () -> { cfg().tabParty = !cfg().tabParty; apply(); })
                        .describe(ChatTab.PARTY.description() + ". On by default."),
                SettingRow.toggle("Guild Tab", () -> cfg().tabGuild,
                        () -> { cfg().tabGuild = !cfg().tabGuild; apply(); })
                        .describe(ChatTab.GUILD.description() + ". On by default."),
                SettingRow.toggle("Co-op Tab", () -> cfg().tabCoop,
                        () -> { cfg().tabCoop = !cfg().tabCoop; apply(); })
                        .describe(ChatTab.COOP.description() + ". Off by default."),
                SettingRow.toggle("DMs Tab", () -> cfg().tabPrivate,
                        () -> { cfg().tabPrivate = !cfg().tabPrivate; apply(); })
                        .describe(ChatTab.PRIVATE.description() + ". On by default."),
                ircTab(),

                SettingRow.label("Keep out of the All tab (still readable on its own tab):"),
                SettingRow.toggle("Mute Public Chat", () -> cfg().mutePublic,
                        () -> { cfg().mutePublic = !cfg().mutePublic; apply(); })
                        .describe("Takes lobby chat out of the All tab. It is not gone - the Public "
                                + "tab still has it, and its counter still ticks. Off by default."),
                SettingRow.toggle("Mute Party Chat", () -> cfg().muteParty,
                        () -> { cfg().muteParty = !cfg().muteParty; apply(); })
                        .describe("Takes party chat out of the All tab; the Party tab keeps it."),
                SettingRow.toggle("Mute Guild Chat", () -> cfg().muteGuild,
                        () -> { cfg().muteGuild = !cfg().muteGuild; apply(); })
                        .describe("Takes guild and officer chat out of the All tab; the Guild tab "
                                + "keeps both."),
                SettingRow.toggle("Mute Co-op Chat", () -> cfg().muteCoop,
                        () -> { cfg().muteCoop = !cfg().muteCoop; apply(); })
                        .describe("Takes co-op chat out of the All tab; the Co-op tab keeps it."),
                SettingRow.toggle("Mute DMs", () -> cfg().mutePrivate,
                        () -> { cfg().mutePrivate = !cfg().mutePrivate; apply(); })
                        .describe("Takes direct messages out of the All tab; the DMs tab keeps them."),
                SettingRow.toggle("Mute IRC", () -> cfg().muteIrc,
                        () -> { cfg().muteIrc = !cfg().muteIrc; apply(); })
                        .describe("Takes the SBS channel out of the All tab; the IRC tab keeps it."),
                SettingRow.label("A mute does nothing while that channel's tab is switched off"),

                SettingRow.label("What the other tabs keep showing:"),
                SettingRow.toggle("Keep Server Messages", () -> cfg().keepSystem,
                        () -> { cfg().keepSystem = !cfg().keepSystem; apply(); })
                        .describe("Keeps everything the server says - rewards, warnings, level-ups "
                                + "- visible on every tab instead of only on All. Off by default, "
                                + "so the Party tab really is nothing but party chat."),
                SettingRow.toggle("Keep SBS Messages", () -> cfg().keepModMessages,
                        () -> { cfg().keepModMessages = !cfg().keepModMessages; apply(); })
                        .describe("Keeps this mod's own [SBS] lines visible on every tab, so an "
                                + "alert you asked for is never swallowed by your own filter. On "
                                + "by default."),

                SettingRow.keybind("Next Tab Key", () -> cfg().cycleKey,
                        key -> { cfg().cycleKey = key; ConfigManager.getInstance().save(); })
                        .describe("Steps to the next tab while you are playing, without opening the "
                                + "chat; the tab you land on is named above your hotbar. Click the "
                                + "row, press a key; Esc unbinds. Unbound by default."));
    }
}
