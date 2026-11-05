/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.abilitytimers.logic;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Tactical Insertion's "Gorilla Tactics" countdown: the 3 seconds between using the ability
 * and being teleported back to the marked spot. Pure state machine, time passed in, so it is unit
 * tested without Minecraft; {@link TacTimerTracker} feeds it.
 *
 * <p><b>Start.</b> The right-click starts the clock at once, unconfirmed. The action bar's
 * "(Gorilla Tactics)" mana-cost text within {@link #CONFIRM_WINDOW_MS} confirms it and re-anchors
 * the clock to that moment, which is closer to when the server actually fired the ability. A
 * click while the 20 s cooldown runs is ignored outright - {@code AbilityCooldowns} owns that
 * cooldown, this class only asks.
 *
 * <p><b>Cancel.</b> An unconfirmed clock is dropped when "This ability is on cooldown for Ns." or
 * "not enough mana" arrives within {@link #FAIL_WINDOW_MS} of the click: the ability did not fire,
 * and a timer counting down to a teleport that never comes is worse than none.
 *
 * <p><b>End.</b> At 0, or early on "Your gorilla tactics have hit N enemies!", which Hypixel sends
 * on the return itself. The hit count is kept for {@link #HITS_SHOWN_MS} for the display.
 */
public final class TacTimer {

    public static final String ITEM_ID = "TACTICAL_INSERTION";

    public static final long DURATION_MS = 3_000L;
    static final long CONFIRM_WINDOW_MS = 300L;
    static final long FAIL_WINDOW_MS = 500L;
    static final long HITS_SHOWN_MS = 2_000L;
    /** How late after the clock ran out a hit line still belongs to this use. */
    private static final long HIT_GRACE_MS = 2_000L;

    private static final Pattern FORMATTING = Pattern.compile("§.");
    private static final Pattern HIT_LINE =
            Pattern.compile("^Your gorilla tactics have hit (\\d+) enem(?:y|ies)!");
    private static final Pattern COOLDOWN_LINE =
            Pattern.compile("(?i)This ability is on cooldown for\\s*\\d+s");
    private static final Pattern NO_MANA = Pattern.compile("(?i)not\\s+enough\\s+mana");
    private static final String ABILITY_TEXT = "(Gorilla Tactics)";

    /** The last accepted click, or -1. */
    private long clickAt = -1;
    /** Where the countdown is anchored, or -1 when none runs. */
    private long startedAt = -1;
    private boolean confirmed;
    private int hits = -1;
    private long hitsUntil;

    /**
     * A right-click with the Tactical Insertion. Returns whether a clock started.
     *
     * @param onCooldown whether the item's cooldown was running before this click
     */
    public boolean onUse(long now, boolean onCooldown) {
        if (onCooldown || remainingMs(now) > 0) {
            return false;
        }
        clickAt = now;
        startedAt = now;
        confirmed = false;
        hits = -1;
        return true;
    }

    /** One action-bar line, colour-stripped. */
    public void onActionBar(String text, long now) {
        if (text == null || startedAt < 0 || confirmed || now - clickAt > FAIL_WINDOW_MS) {
            return;
        }
        if (now - clickAt <= CONFIRM_WINDOW_MS && text.contains(ABILITY_TEXT)) {
            confirmed = true;
            startedAt = now;
        } else if (NO_MANA.matcher(text).find()) {
            startedAt = -1;
        }
    }

    /** One chat line, raw or stripped. */
    public void onChat(String line, long now) {
        if (line == null || clickAt < 0) {
            return;
        }
        String text = FORMATTING.matcher(line).replaceAll("");
        int hit = parseHits(text);
        if (hit >= 0) {
            // Only a line that follows our own click: a stray one (another session, a replayed
            // log) has no clock to end and no count worth showing.
            if (now - clickAt <= DURATION_MS + HIT_GRACE_MS) {
                startedAt = -1;
                hits = hit;
                hitsUntil = now + HITS_SHOWN_MS;
                clickAt = -1;
            }
            return;
        }
        if (startedAt >= 0 && !confirmed && now - clickAt <= FAIL_WINDOW_MS
                && (COOLDOWN_LINE.matcher(text).find() || NO_MANA.matcher(text).find())) {
            startedAt = -1;
        }
    }

    /** Milliseconds until the teleport back, or -1 when no clock runs. */
    public long remainingMs(long now) {
        if (startedAt < 0) {
            return -1;
        }
        long left = startedAt + DURATION_MS - now;
        return left > 0 ? left : -1;
    }

    /** The hit count of the return that just happened, or -1 once it has been shown long enough. */
    public int hitsShown(long now) {
        return hits >= 0 && now < hitsUntil ? hits : -1;
    }

    public boolean confirmed() {
        return confirmed;
    }

    public void reset() {
        clickAt = -1;
        startedAt = -1;
        confirmed = false;
        hits = -1;
    }

    /** N from "Your gorilla tactics have hit N enemies! gg!", or -1 when the line is not that. */
    public static int parseHits(String text) {
        if (text == null) {
            return -1;
        }
        Matcher m = HIT_LINE.matcher(FORMATTING.matcher(text).replaceAll("").strip());
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }
}
