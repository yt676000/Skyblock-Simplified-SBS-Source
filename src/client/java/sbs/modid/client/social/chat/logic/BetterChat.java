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
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig.BetterChatSettings;
import sbs.modid.client.core.mixin.ChatComponentAccessor;
import sbs.modid.client.core.util.ChatDisplayRewrites;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Better Chat: filters, compacts, timestamps and highlights the vanilla chat.
 *
 * <p><b>Ordering.</b> This runs from a mixin on {@link ChatComponent}'s private message-add funnel,
 * which the public entry methods call <b>after</b> the SBS chat parsers (fishing, collection, slayer,
 * party, quests, prices, …) have already read the ORIGINAL line at those public methods. So every
 * tracker still sees the untouched message; Better Chat only decides what is finally displayed –
 * hiding a "you caught X" line here never stops it being counted.
 *
 * <p>The transforms (timestamp / highlight) cancel the original add and re-insert a styled copy
 * through the same private funnel behind a {@link #reentrant} guard, so the re-add is displayed once
 * and is not re-parsed or re-transformed. The compactor rides the same path: it deletes the earlier
 * copy from the accessor's message list and lets the incoming one be added carrying the count, so a
 * repeat reappears at the bottom of the chat rather than silently ticking a number further up.
 *
 * <p><b>It also carries the other features' display rewrites</b> ({@link ChatDisplayRewrites}:
 * Text Editor, Streamer Mode redaction, Number Format, SBS badge), whether or not Better Chat itself
 * is switched on. They used to hook the public add methods and so reached the parsers - the ordering
 * between two injectors at one instruction is not Mixin's to promise. Running them from here makes
 * "parsers first, display second" a property of the call chain instead.
 */
public final class BetterChat {

    private static final BetterChat INSTANCE = new BetterChat();

    private static final DateTimeFormatter TIME_24H = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter TIME_12H = DateTimeFormatter.ofPattern("h:mma", Locale.US);

    /**
     * How far back the compactor looks for an earlier copy of an incoming message. Repeats worth
     * stacking hardly ever arrive back-to-back – a boss telling you to switch attunement is separated
     * by damage lines, ability lines and everyone else's chatter – so "is this the line right above?"
     * misses nearly all of them. Bounded so an identical message from much earlier in the session is
     * left where it is instead of being dragged back down to the bottom of the chat.
     */
    private static final int COMPACT_LOOKBACK = 30;

    /** The {@code (xN)} suffix the compactor writes, and the pattern that reads it back off a line. */
    private static final java.util.regex.Pattern COUNT_SUFFIX =
            java.util.regex.Pattern.compile("^(.*) \\(x(\\d+)\\)$");

    /** True while re-adding a transformed message, so it passes straight through untouched. */
    private volatile boolean reentrant;

    private BetterChat() {
    }

    public static BetterChat getInstance() {
        return INSTANCE;
    }

    private static BetterChatSettings cfg() {
        return ConfigManager.getInstance().get().betterChat;
    }

    /** The Hide-Chat keybind (from {@code CommandKeyMixin}); flips the unfocused-hide toggle. */
    public void onKeyPressed(int key) {
        BetterChatSettings cfg = cfg();
        if (cfg.hideChatKey == 0 || key != cfg.hideChatKey) {
            return;
        }
        cfg.hideWhenUnfocused = !cfg.hideWhenUnfocused;
        ConfigManager.getInstance().save();
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.sendOverlayMessage(Component.literal(cfg.hideWhenUnfocused
                    ? "§7Chat hidden (unfocused)" : "§aChat shown"));
        }
    }

    /** Whether the chat render should be skipped this frame (hide-when-unfocused). */
    public boolean shouldHideRender(ChatComponent chat) {
        BetterChatSettings cfg = cfg();
        return cfg.enabled && cfg.hideWhenUnfocused && !chat.isChatFocused();
    }

    /**
     * Decides the fate of one incoming message at the display funnel. Returns {@code true} to CANCEL
     * the vanilla add (the message was hidden, compacted into the previous line, or replaced by a
     * re-added styled copy); {@code false} to let the original display unchanged.
     */
    public boolean onAddMessage(ChatComponent chat, Component content, Object sig, Object source, Object tag) {
        if (reentrant) {
            return false;   // our own re-added styled copy: display it as-is
        }
        if (content == null) {
            return false;
        }

        // 0. The Ability Damage module owns its own lines and is NOT part of Better Chat, so it gets
        //    to hide them whether or not the Better Chat master toggle is on. The tracker has already
        //    recorded the hit by now (it parses at the public add methods, this is the funnel), and it
        //    checks its own mode before touching the text, so this stays free while it is off.
        if (sbs.modid.client.combat.damage.logic.AbilityDamageTracker.getInstance().shouldHide(content.getString())) {
            return true;
        }

        // 0b. The Sweep card owns the per-chop foraging lines on the same terms, and for the same
        //     reason gets to hide them whether or not Better Chat is on. The tracker has already
        //     recorded the chop by now, and it only ever claims a line it fully parsed.
        if (sbs.modid.client.skills.foraging.logic.SweepTracker.getInstance()
                .shouldHide(content.getString())) {
            return true;
        }

        // 0c. Diana's own chatter - the arrow hint, the ability cooldown, "Warping...". Same terms
        //     again: the toolkit has already read the line, it only claims four exact shapes, and it
        //     answers to its own switch rather than to Better Chat's.
        String dianaLine = content.getString();
        if (sbs.modid.client.combat.diana.logic.DianaGuard.call(sbs.modid.client.combat.diana.logic.DianaGuard.Hook.CHAT_HIDE, dianaLine,
                () -> sbs.modid.client.combat.diana.logic.BurrowChat.getInstance().shouldHide(dianaLine),
                false)) {
            return true;
        }

        // 1. The display rewrites (text rules, redaction, number format, SBS badge). They run here,
        //    below the parsers, and NOT as their own hooks on the public add methods - see
        //    ChatDisplayRewrites for what that cost. Unconditional: they are separate features and
        //    do not answer to the Better Chat switch, which is why this sits above the cfg check.
        Component display = ChatDisplayRewrites.apply(content);

        BetterChatSettings cfg = cfg();
        boolean mention = false;
        if (cfg.enabled) {
            // Everything below reads the line as it will be SHOWN, which is what a filter or a
            // mention rule is written against.
            String text = strip(display.getString());

            // 2. Filters – hide outright. Parsers already ran, so hiding costs no tracking.
            if (isFiltered(text, cfg)) {
                return true;
            }

            // 3. Compactor – an earlier copy of this line is removed and this one carries its count.
            int repeats = cfg.compactor ? takeEarlierCopy(chat, text) : 0;

            // 4. Style the copy for display (timestamp + mention highlight + the repeat counter).
            display = applyTimestamp(display, cfg);
            mention = cfg.highlightMentions && mentions(text, cfg);
            if (mention) {
                display = highlight(display);
            }
            if (repeats > 0) {
                display = display.copy().append(Component.literal(" §7(x" + (repeats + 1) + ")"));
            }
        }

        if (mention && cfg.mentionSound) {
            var player = Minecraft.getInstance().player;
            if (player != null) {
                player.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 0.7f, 1.8f);
            }
        }

        if (display != content) {
            reentrant = true;
            try {
                ((ChatComponentAccessor) chat).skyblockSimplified$addMessage(display,
                        (net.minecraft.network.chat.MessageSignature) sig,
                        (GuiMessageSource) source, (GuiMessageTag) tag);
            } finally {
                reentrant = false;
            }
            return true;   // original cancelled, styled copy added
        }
        return false;      // nothing to change – let the original through
    }

    // ------------------------------------------------------------------ compactor

    /**
     * Removes the most recent earlier copy of {@code text} from the chat and returns how many repeats
     * it already stood for ({@code 0} = there was none, so nothing was removed). The caller re-adds
     * the incoming message carrying {@code that + 1}, which is what makes a repeat <i>resurface</i> at
     * the bottom of the chat instead of quietly ticking a counter somewhere further up: a line telling
     * you to act is worthless if you cannot see that it fired again.
     *
     * <p>The chat's own message list is the state – nothing is cached here, so the counter can never
     * drift out of step with what is on screen (a filtered or hidden message in between is simply not
     * in the list to be found).
     */
    private int takeEarlierCopy(ChatComponent chat, String text) {
        ChatComponentAccessor access = (ChatComponentAccessor) chat;
        List<GuiMessage> all = access.skyblockSimplified$allMessages();
        int window = Math.min(all.size(), COMPACT_LOOKBACK);
        for (int i = 0; i < window; i++) {
            String existing = strip(all.get(i).content().getString());
            java.util.regex.Matcher counted = COUNT_SUFFIX.matcher(existing);
            int repeats = 1;
            if (counted.matches()) {
                existing = counted.group(1);
                repeats = parseCount(counted.group(2));
            }
            // Timestamps / the mention marker are prefixes on the stored line, so the incoming plain
            // text is compared as the tail of it rather than for equality.
            if (existing.endsWith(text)) {
                all.remove(i);
                access.skyblockSimplified$refreshTrimmedMessages();
                return repeats;
            }
        }
        return 0;
    }

    private static int parseCount(String raw) {
        try {
            return Math.max(1, Integer.parseInt(raw));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    // ------------------------------------------------------------------ filters

    private static boolean isFiltered(String text, BetterChatSettings cfg) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (cfg.hideCommandsTooFast && lower.contains("sending commands too fast")) {
            return true;
        }
        if (cfg.hideNotEnoughMana && lower.contains("not enough mana")) {
            return true;
        }
        if (cfg.hideBlocksInTheWay && lower.contains("blocks in the way")) {
            return true;
        }
        if (cfg.hideContaining != null && !cfg.hideContaining.isBlank()) {
            for (String needle : cfg.hideContaining.split(",")) {
                String trimmed = needle.trim().toLowerCase(Locale.ROOT);
                if (!trimmed.isEmpty() && lower.contains(trimmed)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ transforms

    private static Component applyTimestamp(Component content, BetterChatSettings cfg) {
        if (!cfg.timestamps) {
            return content;
        }
        String stamp = LocalTime.now().format(cfg.timestamps24h ? TIME_24H : TIME_12H)
                .toLowerCase(Locale.ROOT);
        return Component.literal("§8[" + stamp + "] §r").append(content);
    }

    private static Component highlight(Component content) {
        return Component.literal("§e§l> §r").append(content);
    }

    private static boolean mentions(String text, BetterChatSettings cfg) {
        String lower = text.toLowerCase(Locale.ROOT);
        var player = Minecraft.getInstance().player;
        if (player != null) {
            String name = player.getGameProfile().name().toLowerCase(Locale.ROOT);
            // Ignore your own outgoing messages ("You: ..." / "name: ..." where you are the sender
            // is fine to highlight too, but the common case is being pinged by others).
            if (lower.contains(name)) {
                return true;
            }
        }
        if (cfg.mentionWords != null && !cfg.mentionWords.isBlank()) {
            for (String word : cfg.mentionWords.split(",")) {
                String trimmed = word.trim().toLowerCase(Locale.ROOT);
                if (!trimmed.isEmpty() && lower.contains(trimmed)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ helpers

    private static String strip(String text) {
        return sbs.modid.client.core.util.PlainText.strip(text);
    }
}
