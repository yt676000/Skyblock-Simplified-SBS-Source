/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.treasurechest.logic;

import sbs.modid.client.skills.mining.treasurechest.model.TreasureChestSignals;

import java.util.Locale;
import java.util.regex.Matcher;

/**
 * Reads a treasure chest's reward block out of chat, one line at a time.
 *
 * <p>A powder line alone proves nothing - powder is named in more than one kind of message - so
 * powder is only read while a reward block is open: after {@code CHEST LOCKPICKED} or
 * {@code LOOT CHEST COLLECTED} and for {@link TreasureChestSignals#REWARD_WINDOW_MS}, or until a
 * separator line closes it. Each of those headers is one chest. The {@code REWARDS} line under it is
 * only a sub-header: the Crystal Nucleus loot bundle sends one too, so it never opens a block, and the
 * bundle's own header closes any block still open.
 *
 * <p>Regex over colour-stripped text, per the repository rule; the stripping is done here so the
 * caller cannot forget it. Pure - text and a clock in, events out.
 */
public final class ChestLootParser {

    /** What one line meant. */
    public enum Kind {
        /** Not ours - most of chat. */
        NONE,
        /** A header opening a new reward block: one chest opened. */
        CHEST,
        /** A powder line inside the block. */
        POWDER,
        /** A line inside the block the powder patterns did not read - logged, so a probe sees it. */
        UNREAD
    }

    /** The result of one line. {@code type} and {@code amount} are set for {@link Kind#POWDER}. */
    public record Event(Kind kind, String type, long amount) {
        static final Event NONE = new Event(Kind.NONE, null, 0L);
        static final Event CHEST = new Event(Kind.CHEST, null, 0L);
        static final Event UNREAD = new Event(Kind.UNREAD, null, 0L);
    }

    /** When the open block's header arrived, or {@code -1} while none is open. */
    private long openedAt = -1L;
    /** Whether the open block has had a line other than a header - the gate for a separator. */
    private boolean hasContent;

    /** One chat line, as displayed. */
    public Event onLine(String raw, long now) {
        if (raw == null) {
            return Event.NONE;
        }
        String line = strip(raw);
        boolean open = openedAt >= 0 && now - openedAt <= TreasureChestSignals.REWARD_WINDOW_MS;
        if (TreasureChestSignals.REWARD_HEADER.matcher(line).matches()) {
            if (open && !hasContent) {
                return Event.NONE;   // a repeat of the header that just opened this block
            }
            openedAt = now;
            hasContent = false;
            return Event.CHEST;
        }
        if (TreasureChestSignals.BUNDLE_HEADER.matcher(line).matches()) {
            clear();   // a Nucleus bundle, never a chest - it gets none of an open block's lines
            return Event.NONE;
        }
        if (!open) {
            openedAt = -1L;
            return Event.NONE;
        }
        if (line.isEmpty() || TreasureChestSignals.REWARDS_SUBHEADER.matcher(line).matches()) {
            return Event.NONE;
        }
        if (TreasureChestSignals.SEPARATOR.matcher(line).matches()) {
            if (hasContent) {
                openedAt = -1L;
            }
            return Event.NONE;
        }
        hasContent = true;
        Matcher nameFirst = TreasureChestSignals.POWDER_NAME_FIRST.matcher(line);
        if (nameFirst.matches()) {
            return powder(nameFirst.group(1), nameFirst.group(2));
        }
        Matcher amountFirst = TreasureChestSignals.POWDER_AMOUNT_FIRST.matcher(line);
        if (amountFirst.matches()) {
            return powder(amountFirst.group(2), amountFirst.group(1));
        }
        return Event.UNREAD;
    }

    /** Lobby change, leaving the island: an open block does not carry across. */
    public void clear() {
        openedAt = -1L;
        hasContent = false;
    }

    private static Event powder(String type, String amount) {
        long value;
        try {
            value = Long.parseLong(amount.replace(",", ""));
        } catch (NumberFormatException e) {
            return Event.UNREAD;
        }
        String lower = type.toLowerCase(Locale.ROOT);
        String name = Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
        return new Event(Kind.POWDER, name, value);
    }

    /** Drops legacy {@code §x} codes and the surrounding whitespace Hypixel centres headers with. */
    public static String strip(String raw) {
        return raw.replaceAll("§.", "").trim();
    }
}
