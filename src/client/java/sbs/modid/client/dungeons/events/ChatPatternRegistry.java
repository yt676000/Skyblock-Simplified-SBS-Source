/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.events;

import net.minecraft.network.chat.Component;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.skills.farming.model.FarmingText;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Central registry for chat-line patterns, the single place dungeon features hook chat instead of
 * adding another hard-wired call to the chat mixin. Every incoming chat line is dispatched once
 * through {@link #dispatch}; each registered {@link Entry} whose pattern matches gets its handler
 * called with the {@link Matcher} (and the raw {@link Component} for hover-text access).
 *
 * <p><b>Patterns match colour-stripped text.</b> The stripping happens <i>here</i>, not in the
 * caller: the previous arrangement documented the contract on this class but left the caller passing
 * {@code Component.getString()} through untouched, so every pattern was in fact matched against text
 * with Hypixel's {@code §} codes still in it. A pattern like {@code \[BOSS\]\s+The Watcher\s*:} then
 * silently fails, because the real line reads {@code §c[BOSS] §fThe Watcher} and there is a colour
 * code sitting exactly where the pattern expects a space. Owning the contract here is what stops it
 * drifting apart from its caller a second time.
 *
 * <p>For one release a pattern that matches the <b>raw</b> line but not the stripped one is still
 * dispatched, and logged as {@code [SBS][ChatPatterns]}. No registered pattern should need it - none
 * of them mention {@code §} - so the log firing at all means a real line is shaped differently from
 * what the audit assumed, and that is worth seeing rather than silently losing a feature.
 *
 * <p>Deliberately tiny and allocation-light on the hot path: a plain list walked per line, patterns
 * pre-compiled at registration. One bad handler can never kill the chat pipeline - each is called
 * inside a guard. Registration is process-lifetime (features register once at init), so no
 * unregister is offered.
 */
public final class ChatPatternRegistry {

    private static final ChatPatternRegistry INSTANCE = new ChatPatternRegistry();

    /** One registered pattern → handler. */
    public record Entry(Pattern pattern, BiConsumer<Matcher, Component> handler, String label) {
    }

    private final List<Entry> entries = new CopyOnWriteArrayList<>();

    private ChatPatternRegistry() {
    }

    public static ChatPatternRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Registers a pattern with its handler. {@code label} is only for diagnostics. The handler runs
     * on the client thread (chat is added there) and must not block.
     */
    public void register(Pattern pattern, BiConsumer<Matcher, Component> handler, String label) {
        if (pattern != null && handler != null) {
            entries.add(new Entry(pattern, handler, label));
        }
    }

    /** Convenience overload for handlers that only need the matched groups, not the raw component. */
    public void register(String regex, java.util.function.Consumer<Matcher> handler, String label) {
        register(Pattern.compile(regex), (matcher, component) -> handler.accept(matcher), label);
    }

    /**
     * Runs one chat line through every registered pattern. Called once per line from the chat mixin
     * funnel with the line exactly as it arrived; {@code raw} carries the hover text when a handler
     * needs it. Colour codes are stripped here before any pattern sees the text.
     */
    public void dispatch(String line, Component raw) {
        if (line == null || entries.isEmpty()) {
            return;
        }
        String text = FarmingText.strip(line);
        // DEV-ONLY: raw-line log only
        if (sbs.modid.client.core.dev.DevMode.ACTIVE) {
            // The capture the reminder patterns are written from: both forms, so a pattern can be
            // checked against what actually arrives instead of against an assumed shape.
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][ChatPatterns] raw={} stripped={}", line, text);
        }
        boolean hasCodes = text.length() != line.length();
        for (Entry entry : entries) {
            try {
                Matcher matcher = entry.pattern().matcher(text);
                if (matcher.find()) {
                    entry.handler().accept(matcher, raw);
                    continue;
                }
                if (hasCodes) {
                    Matcher legacy = entry.pattern().matcher(line);
                    if (legacy.find()) {
                        SkyblockSimplifiedSBS.LOGGER.warn(
                                "[SBS][ChatPatterns] '{}' matched the RAW line but not the stripped "
                                        + "one - the pattern depends on colour codes: {}",
                                entry.label(), line);
                        entry.handler().accept(legacy, raw);
                    }
                }
            } catch (RuntimeException e) {
                SkyblockSimplifiedSBS.LOGGER.debug("[SBS][Dungeon] chat handler '{}' failed: {}",
                        entry.label(), e.toString());
            }
        }
    }
}
