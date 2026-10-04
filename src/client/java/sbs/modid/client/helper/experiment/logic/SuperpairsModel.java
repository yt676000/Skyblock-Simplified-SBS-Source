/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.experiment.logic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Superpairs, the decisions only: what is under every card seen so far, which cards are matched,
 * and which covered card to turn next. Fed one plain board per frame, so the Server Scanner
 * recordings drive it in tests.
 *
 * <p><b>The board (CONFIRMED, Server Scanner 2026-10-04, Metaphysical).</b> Cards are covered by
 * {@code cyan_stained_glass} whose NAME is the phase: "Click any button!" (first card), "Click a
 * second button!" (second card), "?" (~650 ms after a mismatch; clicks are dropped without cost).
 * The board is every slot that has shown that cover - never a slot range, since the tiers differ in
 * size. A matched pair simply stays revealed. Cards carry no SkyBlock id, and two DIFFERENT pairs
 * can share a name ("143k Enchanting Exp" as magenta dye and as pink dye), so a card is keyed by
 * item id + name + lore ({@link CardKey}). A power-up (lore "Instant powerup!", e.g. feather
 * "Gained +3 Clicks") has no partner. At "Remaining Clicks: 0" every card is revealed with an extra
 * "Click any to proceed!" lore line, which the key ignores.
 *
 * <p>Highlight only: there is no misclick guard here (the guard never touches Superpairs, and the
 * clicks it could save - those during "?" - are dropped by Hypixel without cost anyway).
 *
 * <p><b>Tiers.</b> CONFIRMED: Metaphysical, 28 cards (the recording). ASSUMED: every lower tier.
 * The board is whatever has shown the cover, so a smaller grid is read the same way (see
 * {@code SuperpairsTiersTest}); no slot range or card count is assumed.
 */
final class SuperpairsModel {

    static final String COVER_ID = "cyan_stained_glass";
    static final String POWERUP = "Instant powerup!";
    static final String PROCEED = "Click any to proceed!";

    private static final java.util.regex.Pattern GAINED_CLICKS =
            java.util.regex.Pattern.compile("(?i)^Gained \\+\\d+ Clicks?!?$");
    private static final java.util.regex.Pattern PLUS_CLICKS =
            java.util.regex.Pattern.compile("(?i)^\\+\\d+ Clicks?!?$");

    /**
     * The board's phase, read off the cover's name. {@code INSTANT} is "Next button is instantly
     * rewarded!" (after an Instant Find power-up: the next card turned brings its partner with it);
     * {@code OTHER} is a cover with a name not seen yet - still a cover, never a card.
     */
    enum Phase { NONE, FIRST, SECOND, WAIT, INSTANT, OTHER }

    /** What a card shows, for pairing: item id, name, and lore without the end-of-game lines. */
    record CardKey(String id, String name, List<String> lore) {

        static CardKey of(PlainItem item) {
            List<String> lore = new ArrayList<>(item.lore());
            while (!lore.isEmpty()) {
                String last = lore.get(lore.size() - 1);
                if (last.isBlank() || last.equals(PROCEED)) {
                    lore.remove(lore.size() - 1);
                } else {
                    break;
                }
            }
            return new CardKey(item.id(), item.name(), List.copyOf(lore));
        }
    }

    /** The phase a single cover tile announces, or {@code null} when it is not a cover. */
    static Phase coverPhase(PlainItem item) {
        if (item == null || !item.id().equals(COVER_ID)) {
            return null;
        }
        // Every cyan glass on the board is the cover, whatever it says: an unknown cover name once
        // made all 28 covers read as cards, overwrote the memory and suggested "pairs" of covers.
        return switch (item.name()) {
            case "Click any button!" -> Phase.FIRST;
            case "Click a second button!" -> Phase.SECOND;
            case "?" -> Phase.WAIT;
            case "Next button is instantly rewarded!" -> Phase.INSTANT;
            default -> Phase.OTHER;
        };
    }

    /**
     * A power-up: a card without a partner. Seen (Metaphysical, 2026-10-04): feather "Gained +3
     * Clicks" with lore "Instant powerup!", and diamond "Instant Find" with lore "+1 Click" /
     * "Powerup for next click!". Matched by phrase, not by item, so other variants of the same
     * wording are caught too.
     */
    static boolean isPowerup(PlainItem item) {
        if (GAINED_CLICKS.matcher(item.name()).matches()) {
            return true;
        }
        for (String line : item.lore()) {
            String l = line.trim();
            if (l.toLowerCase(java.util.Locale.ROOT).contains("powerup") || PLUS_CLICKS.matcher(l).matches()) {
                return true;
            }
        }
        return false;
    }

    private final Set<Integer> board = new HashSet<>();
    /** Slot -> the card last seen there (sorted by slot, so suggestions are stable). */
    private final Map<Integer, CardKey> memory = new TreeMap<>();
    private final Set<Integer> powerups = new HashSet<>();
    private final Set<Integer> matched = new HashSet<>();
    private final Set<Integer> revealed = new HashSet<>();
    private Phase phase = Phase.NONE;

    void reset() {
        board.clear();
        memory.clear();
        powerups.clear();
        matched.clear();
        revealed.clear();
        phase = Phase.NONE;
    }

    /** One frame of the menu: {@code board[slot]}, {@code null} for empty. */
    void onFrame(PlainItem[] items) {
        Phase agreed = null;
        boolean mixed = false;
        for (int slot = 0; slot < items.length; slot++) {
            Phase p = coverPhase(items[slot]);
            if (p == null) {
                continue;
            }
            board.add(slot);
            if (agreed == null) {
                agreed = p;
            } else if (agreed != p) {
                mixed = true;
            }
        }
        // Mid-burst frames show two cover names at once; they decide nothing.
        Phase now = agreed == null ? Phase.NONE : mixed ? null : agreed;
        if (now != null) {
            phase = now;
        }

        revealed.clear();
        Map<CardKey, Integer> visibleCount = new LinkedHashMap<>();
        for (int slot : board) {
            PlainItem item = slot < items.length ? items[slot] : null;
            if (item == null || item.isPane() || coverPhase(item) != null) {
                continue;
            }
            if (isPowerup(item)) {
                powerups.add(slot);
                memory.remove(slot);
                continue;
            }
            CardKey key = CardKey.of(item);
            memory.put(slot, key);
            revealed.add(slot);
            visibleCount.merge(key, 1, Integer::sum);
        }
        // Cards still face up when the next pair starts are matched - but only when their partner
        // is face up too, so a mismatched card a burst has not re-covered yet never counts.
        if (now == Phase.FIRST) {
            for (int slot : revealed) {
                if (visibleCount.getOrDefault(memory.get(slot), 0) >= 2) {
                    matched.add(slot);
                }
            }
        }
    }

    Phase phase() {
        return phase;
    }

    /** Slots known to belong to the board. */
    Set<Integer> board() {
        return Collections.unmodifiableSet(board);
    }

    /** Slot -> the card remembered there. */
    Map<Integer, CardKey> memory() {
        return Collections.unmodifiableMap(memory);
    }

    boolean isMatched(int slot) {
        return matched.contains(slot);
    }

    boolean isRevealed(int slot) {
        return revealed.contains(slot);
    }

    boolean isPowerupSlot(int slot) {
        return powerups.contains(slot);
    }

    /**
     * The covered card(s) to click: during "second button", the known partner of the face-up card;
     * during "any button", both cards of the first fully known unmatched pair. Empty when nothing
     * useful is known, and always empty during "?".
     */
    List<Integer> suggestion() {
        if (phase == Phase.SECOND) {
            Integer open = null;
            for (int slot : revealed) {
                if (!matched.contains(slot)) {
                    if (open != null) {
                        return List.of();   // two unmatched cards up: not a state we can read
                    }
                    open = slot;
                }
            }
            if (open == null) {
                return List.of();
            }
            CardKey key = memory.get(open);
            for (Map.Entry<Integer, CardKey> e : memory.entrySet()) {
                int slot = e.getKey();
                if (slot != open && e.getValue().equals(key) && covered(slot)) {
                    return List.of(slot);
                }
            }
            return List.of();
        }
        if (phase == Phase.FIRST) {
            List<List<Integer>> pairs = knownPairs();
            return pairs.isEmpty() ? List.of() : pairs.get(0);
        }
        return List.of();
    }

    /** Every pair of face-down, unmatched cards whose faces are both known, lowest slots first. */
    List<List<Integer>> knownPairs() {
        Map<CardKey, List<Integer>> byKey = new LinkedHashMap<>();
        for (Map.Entry<Integer, CardKey> e : memory.entrySet()) {
            if (covered(e.getKey())) {
                byKey.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
            }
        }
        List<List<Integer>> out = new ArrayList<>();
        for (List<Integer> slots : byKey.values()) {
            for (int i = 0; i + 1 < slots.size(); i += 2) {
                out.add(List.of(slots.get(i), slots.get(i + 1)));
            }
        }
        return out;
    }

    /** Face down and not matched: a card a click would turn. */
    private boolean covered(int slot) {
        return !revealed.contains(slot) && !matched.contains(slot) && !powerups.contains(slot);
    }

    String debug() {
        return "phase=" + phase + " board=" + board.size() + " known=" + memory.size() + " matched="
                + matched.size() + " powerups=" + powerups.size() + " suggest=" + suggestion();
    }
}
