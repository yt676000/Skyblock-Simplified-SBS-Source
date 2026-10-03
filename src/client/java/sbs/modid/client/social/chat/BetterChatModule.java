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
import sbs.modid.client.ui.settings.SettingRow;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;

import java.util.List;

/**
 * Better Chat module (Party &amp; Chat): restyle, filter, compact and hide the vanilla chat. All of it
 * runs after the other SBS chat logic, so trackers keep seeing the original lines. Self-registered
 * via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class BetterChatModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public BetterChatModule() {
    }

    @Override
    public String id() {
        return "better_chat";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.PARTY_CHAT;
    }

    @Override
    public String displayName() {
        return "Better Chat";
    }

    @Override
    public String description() {
        return "Compact, timestamp, highlight, filter or hide your chat";
    }

    @Override
    public int accentColor() {
        return 0xFF7ED957;
    }

    private static SBSConfig.BetterChatSettings cfg() {
        return ConfigManager.getInstance().get().betterChat;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Better Chat", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Chat quality-of-life: stack repeated messages, timestamps, "
                                + "mention highlights and spam filters. Everything here only "
                                + "changes what YOU see - the messages still reach every tracker."),
                SettingRow.label("Runs after all other chat logic, so trackers stay correct"),

                SettingRow.toggle("Compact Duplicates (xN)", () -> cfg().compactor,
                        () -> { cfg().compactor = !cfg().compactor; save(); })
                        .describe("A repeated message is shown once with a counter (x3) instead of "
                                + "filling the chat. It still stacks when other lines arrive in "
                                + "between - the repeat moves back down to the newest position "
                                + "with its count raised, so you can see it fired again."),

                SettingRow.toggle("Timestamps", () -> cfg().timestamps,
                        () -> { cfg().timestamps = !cfg().timestamps; save(); })
                        .describe("Puts the clock time in front of every chat message."),
                SettingRow.toggle("24-Hour Time", () -> cfg().timestamps24h,
                        () -> { cfg().timestamps24h = !cfg().timestamps24h; save(); })
                        .describe("Timestamps as 21:30 instead of 9:30 PM."),

                SettingRow.toggle("Highlight Mentions", () -> cfg().highlightMentions,
                        () -> { cfg().highlightMentions = !cfg().highlightMentions; save(); })
                        .describe("Colors messages containing your name (or your extra words) so "
                                + "being spoken to stands out from the scroll."),
                SettingRow.text("Mention Words", "extra words, comma-separated", 128,
                        () -> cfg().mentionWords == null ? "" : cfg().mentionWords,
                        v -> { cfg().mentionWords = v == null ? "" : v; save(); })
                        .describe("Extra words that count as mentioning you, separated by commas "
                                + "- nicknames, your guild tag, an item you are selling."),
                SettingRow.toggle("Mention Sound", () -> cfg().mentionSound,
                        () -> { cfg().mentionSound = !cfg().mentionSound; save(); })
                        .describe("Plays a ping when a mention arrives."),

                SettingRow.toggle("Hide Chat (unfocused)", () -> cfg().hideWhenUnfocused,
                        () -> { cfg().hideWhenUnfocused = !cfg().hideWhenUnfocused; save(); })
                        .describe("Hides the chat completely while you are not typing in it - a "
                                + "clean screen, chat appears when you press T."),
                SettingRow.keybind("Hide Chat Key", () -> cfg().hideChatKey,
                        key -> { cfg().hideChatKey = key; save(); })
                        .describe("A key that hides and shows the chat on demand. Click the row, "
                                + "press a key; Esc unbinds."),

                SettingRow.label("Filters (message still counts for trackers, just not shown):"),
                SettingRow.toggle("Hide 'commands too fast'", () -> cfg().hideCommandsTooFast,
                        () -> { cfg().hideCommandsTooFast = !cfg().hideCommandsTooFast; save(); })
                        .describe("Hides Hypixel's 'You are sending commands too fast' spam."),
                SettingRow.toggle("Hide 'not enough mana'", () -> cfg().hideNotEnoughMana,
                        () -> { cfg().hideNotEnoughMana = !cfg().hideNotEnoughMana; save(); })
                        .describe("Hides the 'You do not have enough mana' spam from mashing an "
                                + "ability."),
                SettingRow.toggle("Hide 'blocks in the way'", () -> cfg().hideBlocksInTheWay,
                        () -> { cfg().hideBlocksInTheWay = !cfg().hideBlocksInTheWay; save(); })
                        .describe("Hides the 'There are blocks in the way' teleport spam."),
                SettingRow.text("Hide Containing", "text, comma-separated", 256,
                        () -> cfg().hideContaining == null ? "" : cfg().hideContaining,
                        v -> { cfg().hideContaining = v == null ? "" : v; save(); })
                        .describe("Your own filters: any message containing one of these "
                                + "comma-separated texts is hidden. The messages still count for "
                                + "every tracker - they are only not drawn."));
    }
}
