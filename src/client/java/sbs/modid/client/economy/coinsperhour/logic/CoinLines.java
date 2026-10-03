/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.coinsperhour.logic;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The chat lines that explain a purse change: which way the coins went and why.
 *
 * <p><b>Every pattern below was found in the play instance's logs</b> - 82k chat lines, 2026-07-08
 * to 2026-09-24, grepped 2026-09-25 - unless marked otherwise. Anchored at the start, because the
 * same sentences also turn up quoted inside other players' chat ({@code "[#] ⛃ [MVP+] X: You have
 * deposited ..."} is in those logs), and a quote must not move anybody's ledger.
 *
 * <p>Lines that move no coins are deliberately absent: {@code [Bazaar] Claimed 64x Item worth ...
 * bought for ...} delivers items, {@code Sell Offer Setup!} escrows items, and bank interest lands in
 * the bank, not the purse.
 *
 * <p>Pure: text in, event out.
 */
public final class CoinLines {

    /** Which way a line says the coins went. */
    public enum Kind {
        /** Coins came in for something you sold or did. */
        EARN,
        /** Coins went out on something you bought. */
        SPEND,
        /** Coins moved between your own pockets - bank, a refunded order. Never a rate. */
        TRANSFER
    }

    /** Where they came from. {@link #UNKNOWN} is a purse change no line explained. */
    public enum Source { BAZAAR, AUCTION, NPC, BANK, PURCHASE, OTHER, UNKNOWN }

    /**
     * One explained coin movement. {@code claim} marks a Bazaar or auction claim, which the player
     * decides whether to count as earnings (setting). {@code in} says the coins entered the purse.
     */
    public record Event(Kind kind, Source source, long amount, boolean claim, boolean in) {
    }

    private record Rule(Pattern pattern, Kind kind, Source source, boolean claim, boolean in) {
    }

    private static final String N = "([\\d,]+(?:\\.\\d+)?)";

    private static final List<Rule> RULES = List.of(
            // Bank, personal and co-op wordings. Seen 15 / 14 / 11 / 11 times.
            rule("^You have deposited " + N + " coins!", Kind.TRANSFER, Source.BANK, false, false),
            rule("^Deposited " + N + " coins!", Kind.TRANSFER, Source.BANK, false, false),
            rule("^You have withdrawn " + N + " coins!", Kind.TRANSFER, Source.BANK, false, true),
            rule("^Withdrew " + N + " coins!", Kind.TRANSFER, Source.BANK, false, true),
            // Bazaar. Instant sell / sell-offer claim / instant buy / buy-order escrow / refund.
            rule("^\\[Bazaar\\] Sold [\\d,]+x .+ for " + N + " coins!$", Kind.EARN, Source.BAZAAR, false, true),
            rule("^\\[Bazaar\\] Claimed " + N + " coins from selling ", Kind.EARN, Source.BAZAAR, true, true),
            rule("^\\[Bazaar\\] Bought [\\d,]+x .+ for " + N + " coins!$", Kind.SPEND, Source.BAZAAR, false, false),
            rule("^\\[Bazaar\\] Buy Order Setup! .+ for " + N + " coins\\.$", Kind.SPEND, Source.BAZAAR, false, false),
            rule("^\\[Bazaar\\] Cancelled! Refunded " + N + " coins ", Kind.TRANSFER, Source.BAZAAR, false, true),
            // Auction House: collecting a sold auction.
            rule("^You collected " + N + " coins from selling .+ in an auction!$",
                    Kind.EARN, Source.AUCTION, true, true),
            // NPC sell and buy-back.
            rule("^You sold .+ x[\\d,]+ for " + N + " Coins!$", Kind.EARN, Source.NPC, false, true),
            rule("^You bought back .+ for " + N + " Coins!$", Kind.SPEND, Source.NPC, false, false),
            // "You purchased X for N coins!" is both an NPC shop and a BIN buy - it cannot be told apart
            // from the line, so it is its own bucket rather than a guess at either.
            rule("^You purchased .+ for " + N + " coins!$", Kind.SPEND, Source.PURCHASE, false, false),
            // Death penalty. Only ever seen QUOTED in the logs, never as the player's own line, so the
            // wording is ESTIMATED; an unmatched death still shows up as unknown spending.
            rule("^You died and lost " + N + " coins!$", Kind.SPEND, Source.OTHER, false, false));

    private CoinLines() {
    }

    private static Rule rule(String regex, Kind kind, Source source, boolean claim, boolean in) {
        return new Rule(Pattern.compile(regex), kind, source, claim, in);
    }

    /** The coin movement a chat line announces, or {@code null} when it announces none. */
    public static Event classify(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        String line = raw.replaceAll("§.", "").trim();
        for (Rule rule : RULES) {
            Matcher matcher = rule.pattern.matcher(line);
            if (matcher.find()) {
                long amount = parse(matcher.group(1));
                return amount <= 0 ? null : new Event(rule.kind, rule.source, amount, rule.claim, rule.in);
            }
        }
        return null;
    }

    /** "1,234.5" -> 1235. */
    static long parse(String digits) {
        try {
            return Math.round(Double.parseDouble(digits.replace(",", "")));
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    /** Scoreboard purse: "Purse: 621,748,621" (instance logs); "Piggy:" is the older spelling. */
    private static final Pattern PURSE = Pattern.compile("^(?:Purse|Piggy):\\s*([\\d,]+(?:\\.\\d+)?)");

    /** The purse value on a colour-stripped sidebar line, or {@code -1} when it is not that line. */
    public static long purse(String line) {
        if (line == null) {
            return -1L;
        }
        Matcher matcher = PURSE.matcher(line.trim());
        return matcher.find() ? parse(matcher.group(1)) : -1L;
    }
}
