/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.trade.logic;

import sbs.modid.client.economy.trade.model.TradeSlot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads Hypixel's player-to-player trade menu from plain slot text.
 *
 * <p>Layout from the 2026-11-22 layout scan (see {@code docs/knowledge/trade-guard.md}): 9x5 grid,
 * divider panes down column 4, my offer left (0-3, 9-12, 18-21, 27-30), theirs right (5-8, 14-17,
 * 23-26, 32-35), the coins button at 36, my accept button at 39 and the partner's status at 41.
 * All matching is on lower-cased plain text and never on the ⇦ ⇨ arrows.
 */
public final class TradeLayout {

    public static final Pattern TITLE = Pattern.compile("^You\\s{2,}(\\S+)$");
    public static final int[] DIVIDERS = {4, 13, 22, 31, 40};
    public static final int[] MY_SLOTS = {0, 1, 2, 3, 9, 10, 11, 12, 18, 19, 20, 21, 27, 28, 29, 30};
    public static final int[] THEIR_SLOTS = {5, 6, 7, 8, 14, 15, 16, 17, 23, 24, 25, 26, 32, 33, 34, 35};
    public static final int COINS_BUTTON = 36;
    public static final int ACCEPT = 39;
    public static final int PARTNER = 41;

    private static final Pattern COIN_NAME = Pattern.compile("^([\\d.,]+\\s*[kmbt]?)\\s+coins?$");
    private static final Pattern TIMER = Pattern.compile("\\((\\d+)\\)");

    /** My accept button (slot 39). The last two are not scanned yet and matched generically. */
    public enum AcceptState { NOTHING_OFFERED, GIFT_WARNING, DEAL_TIMER, READY, ACCEPTED, UNKNOWN, MISSING }

    /** The partner's status (slot 41). */
    public enum PartnerState { NEW_DEAL, PENDING_THEIR_CONFIRM, DEAL_TIMER, CONFIRMED, UNKNOWN, MISSING }

    /**
     * One parsed trade menu. {@code mine}/{@code theirs} are offer slots by index (coin heads
     * included, as items with {@code print.coins()} set). {@code timerSeconds} is the deal timer
     * count, {@code 0} when no timer runs. {@code acceptRaw}/{@code partnerRaw} keep the text of an
     * UNKNOWN state for the log.
     */
    public record Menu(String partner, Map<Integer, TradeSlot> mine, Map<Integer, TradeSlot> theirs,
                       AcceptState accept, int timerSeconds, PartnerState partnerState,
                       String acceptRaw, String partnerRaw) {
    }

    private TradeLayout() {
    }

    /** The partner's IGN from the container title, or {@code null} when this is not a trade. */
    public static String partner(String plainTitle) {
        if (plainTitle == null) {
            return null;
        }
        Matcher m = TITLE.matcher(plainTitle.trim());
        return m.matches() ? m.group(1) : null;
    }

    /** Whether all five divider panes are present - the second check after the title. */
    public static boolean dividersPresent(Map<Integer, TradeSlot> slots) {
        for (int d : DIVIDERS) {
            TradeSlot s = slots.get(d);
            if (s == null || !s.material().contains("glass_pane")
                    || !lower(s.name()).contains("your stuff")) {
                return false;
            }
        }
        return true;
    }

    /** The parsed menu, or {@code null} when the title or the dividers say it is not a trade. */
    public static Menu parse(String plainTitle, List<TradeSlot> slotList) {
        String partner = partner(plainTitle);
        if (partner == null) {
            return null;
        }
        Map<Integer, TradeSlot> slots = new TreeMap<>();
        for (TradeSlot s : slotList) {
            slots.put(s.slot(), s);
        }
        if (!dividersPresent(slots)) {
            return null;
        }
        TradeSlot accept = slots.get(ACCEPT);
        TradeSlot status = slots.get(PARTNER);
        AcceptState a = acceptState(accept);
        return new Menu(partner, pick(slots, MY_SLOTS), pick(slots, THEIR_SLOTS), a,
                a == AcceptState.DEAL_TIMER ? timerSeconds(accept) : 0, partnerState(status),
                raw(accept), raw(status));
    }

    public static AcceptState acceptState(TradeSlot s) {
        if (s == null) {
            return AcceptState.MISSING;
        }
        String name = lower(s.name());
        String mat = s.material();
        if (name.startsWith("deal timer") || mat.equals("yellow_terracotta")) {
            return AcceptState.DEAL_TIMER;
        }
        if (name.startsWith("warning") || mat.equals("orange_terracotta")) {
            return AcceptState.GIFT_WARNING;
        }
        if (name.equals("trading!")) {
            return AcceptState.NOTHING_OFFERED;
        }
        String lore = lower(String.join("\n", s.lore()));
        // Not scanned yet: matched by wording so a scan can confirm or correct it.
        if (name.contains("pending") || name.contains("waiting") || name.contains("accepted")
                || lore.contains("waiting for the other")) {
            return AcceptState.ACCEPTED;
        }
        if (name.contains("accept") || name.contains("confirm") || name.contains("deal!")) {
            return AcceptState.READY;
        }
        return AcceptState.UNKNOWN;
    }

    public static PartnerState partnerState(TradeSlot s) {
        if (s == null) {
            return PartnerState.MISSING;
        }
        String name = lower(s.name());
        if (name.startsWith("other player confirmed") || s.material().equals("lime_dye")) {
            return PartnerState.CONFIRMED;
        }
        if (name.startsWith("pending their confirm")) {
            return PartnerState.PENDING_THEIR_CONFIRM;
        }
        if (name.startsWith("deal timer")) {
            return PartnerState.DEAL_TIMER;
        }
        if (name.startsWith("new deal")) {
            return PartnerState.NEW_DEAL;
        }
        return PartnerState.UNKNOWN;
    }

    static int timerSeconds(TradeSlot s) {
        Matcher m = TIMER.matcher(s.name());
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return Math.max(0, s.count());
    }

    /**
     * The coin total of a coin offer head, or {@code -1}. Recognised by name and lore, never by
     * slot: their side has not been scanned yet.
     */
    public static long coinOffer(String material, String name, List<String> lore) {
        if (material == null || !material.equals("player_head")) {
            return -1;
        }
        Matcher m = COIN_NAME.matcher(lower(name).trim());
        if (!m.matches()) {
            return -1;
        }
        boolean lumpSum = false;
        String last = null;
        for (String line : lore) {
            String l = lower(line).trim();
            if (l.contains("lump-sum") || l.contains("total coins offered")) {
                lumpSum = true;
            }
            if (!l.isEmpty()) {
                last = l;
            }
        }
        if (!lumpSum) {
            return -1;
        }
        long total = last == null ? -1 : parseCoins(last);
        return total >= 0 ? total : parseCoins(m.group(1));
    }

    /** "1,234", "1.5M", "2B", "750k" → coins; {@code -1} when it is no number. */
    public static long parseCoins(String text) {
        if (text == null) {
            return -1;
        }
        String t = text.toLowerCase(Locale.ROOT).replace(",", "").replace("coins", "").trim();
        if (t.isEmpty()) {
            return -1;
        }
        double mult = 1;
        char last = t.charAt(t.length() - 1);
        switch (last) {
            case 'k' -> mult = 1e3;
            case 'm' -> mult = 1e6;
            case 'b' -> mult = 1e9;
            case 't' -> mult = 1e12;
            default -> { }
        }
        if (mult != 1) {
            t = t.substring(0, t.length() - 1).trim();
        }
        try {
            return Math.round(Double.parseDouble(t) * mult);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static Map<Integer, TradeSlot> pick(Map<Integer, TradeSlot> slots, int[] indices) {
        Map<Integer, TradeSlot> out = new TreeMap<>();
        for (int i : indices) {
            TradeSlot s = slots.get(i);
            if (s != null) {
                out.put(i, s);
            }
        }
        return out;
    }

    private static String raw(TradeSlot s) {
        if (s == null) {
            return "<empty>";
        }
        List<String> parts = new ArrayList<>();
        parts.add(s.material());
        parts.add('"' + s.name() + '"');
        if (!s.lore().isEmpty()) {
            parts.add("lore=" + s.lore());
        }
        return String.join(" ", parts);
    }

    static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
