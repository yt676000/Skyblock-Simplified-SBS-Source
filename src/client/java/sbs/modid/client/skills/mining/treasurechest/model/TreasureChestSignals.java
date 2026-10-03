/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.treasurechest.model;

import sbs.modid.client.helper.rift.model.Certainty;

import java.util.regex.Pattern;

/**
 * Everything the treasure-chest helper assumes about the game, in one place, each with how sure we
 * are of it.
 *
 * <p><b>The chat values are confirmed; the block, particle and click values are not.</b> Everything
 * was first written 2026-09-24 from the task brief, before any capture, and tagged
 * {@link Certainty#ESTIMATED}. On 2026-10-03 the spawn line, the two reward headers, the
 * {@code REWARDS} sub-header, the Nucleus bundle header and the name-first powder line were checked
 * against the maintainer's own chat logs (the 1.8.9 client on the same Hypixel server, 955 log files
 * from 2025-04 to 2025-12) and promoted to {@link Certainty#CONFIRMED}. The chest block, the claim
 * window and the lockpick burst have no capture yet and stay {@code ESTIMATED} - a probe run (see
 * {@code docs/features/hollows-treasure-chest.md}) promotes them one at a time: replace the value,
 * change the tag, and add the date it was seen.
 */
public final class TreasureChestSignals {

    private TreasureChestSignals() {
    }

    // ------------------------------------------------------------------ chat

    /**
     * The spawn line, exactly {@code You uncovered a treasure chest!} (514 times in the logs). Loose
     * about "have" and the trailing punctuation, strict about the rest - a looser match would catch
     * another player quoting it in party chat.
     */
    public static final Pattern SPAWN =
            Pattern.compile("^You (?:have )?uncovered a treasure chest[!.]?$", Pattern.CASE_INSENSITIVE);
    public static final Certainty SPAWN_CERTAINTY = Certainty.CONFIRMED;

    /**
     * The headers that open a reward block, one chest each: {@code CHEST LOCKPICKED} (95 times in the
     * logs) or {@code LOOT CHEST COLLECTED} (99 times), sent with two leading spaces and one trailing
     * space.
     */
    public static final Pattern REWARD_HEADER =
            Pattern.compile("^(?:CHEST LOCKPICKED|LOOT CHEST COLLECTED)$");
    public static final Certainty REWARD_HEADER_CERTAINTY = Certainty.CONFIRMED;

    /**
     * The sub-header two lines under a reward header. Not a header of its own: the Crystal Nucleus
     * loot bundle sends the same line, so by itself it says nothing about a chest.
     */
    public static final Pattern REWARDS_SUBHEADER = Pattern.compile("^REWARDS$");

    /**
     * The Crystal Nucleus loot bundle's header (95 times in the logs). It closes any open chest
     * block, so a bundle arriving inside a chest's window cannot hand its lines to that chest.
     */
    public static final Pattern BUNDLE_HEADER = Pattern.compile("^CRYSTAL NUCLEUS LOOT BUNDLE$");

    /**
     * {@code Gemstone Powder x1,315} - the name-first shape, the only one seen inside a chest block
     * (Gemstone 536 times, Mithril 53).
     */
    public static final Pattern POWDER_NAME_FIRST =
            Pattern.compile("^([A-Za-z]+) Powder\\s*x\\s*([\\d,]+)$");
    public static final Certainty POWDER_LINE_CERTAINTY = Certainty.CONFIRMED;

    /**
     * {@code +1,234 Gemstone Powder} / {@code 1,234 Gemstone Powder} - the amount-first shape. Never
     * seen inside a chest block in the logs; it only appears in other messages (first gemstone of the
     * day, Golden Goblin), which arrive outside a block and stay ignored. Kept in case the chest
     * wording changes, not because it has been observed there.
     */
    public static final Pattern POWDER_AMOUNT_FIRST =
            Pattern.compile("^\\+?([\\d,]+)\\s+([A-Za-z]+) Powder$");
    public static final Certainty POWDER_AMOUNT_FIRST_CERTAINTY = Certainty.ESTIMATED;

    /** A line made only of separator glyphs, which closes a reward block once it has content. */
    public static final Pattern SEPARATOR = Pattern.compile("^[\\u25AC\\-=_\\s]{6,}$");

    /** How long after a header powder lines still belong to that chest. */
    public static final long REWARD_WINDOW_MS = 3_000L;

    // ------------------------------------------------------------------ the block

    /** How far from the player a chest may appear and still be the one the line announced. */
    public static final double CLAIM_RADIUS = 6.0;
    /** How far apart in time the spawn line and the block update may arrive, either way round. */
    public static final long CLAIM_WINDOW_MS = 3_000L;
    public static final Certainty CLAIM_CERTAINTY = Certainty.ESTIMATED;

    /** A claimed chest that is still standing after this long is dropped. */
    public static final long CHEST_LIFETIME_MS = 120_000L;

    /** At most this many claimed chests at once. */
    public static final int MAX_CHESTS = 4;

    // ------------------------------------------------------------------ the lockpick burst

    /**
     * How far outside the chest's block a particle may sit and still belong to it. The burst is
     * expected on or just off a face; 0.3 keeps a chest one block over from reaching in.
     */
    public static final double PARTICLE_GROWTH = 0.3;
    /** The marker hides this long after the last burst. */
    public static final long MARKER_STALE_MS = 1_500L;
    /** After your click, a burst this close to the clicked spot is ignored for {@link #CLICK_GRACE_MS}. */
    public static final double SAME_SPOT = 0.15;
    public static final long CLICK_GRACE_MS = 400L;
    public static final Certainty LOCKPICK_CERTAINTY = Certainty.ESTIMATED;
}
