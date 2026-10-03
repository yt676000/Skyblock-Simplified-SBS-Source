/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.garden.logic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The decisions behind the Garden visitor highlight, kept pure so they are unit-tested: which visitor
 * a nametag names, and what is known about that visitor's offer.
 *
 * <p><b>A nametag only counts when it names a visitor the tab lists.</b> How Hypixel attaches a
 * visitor's name to its entity (its own name, or an armor-stand line above) has not been captured
 * yet - see {@code docs/features/garden-visitor-highlight.md} - so the renderer offers every
 * candidate text near a player-shaped NPC and this class accepts one only if it is one of the names
 * in the tab's {@code Visitors:} widget. A stray "CLICK" line or a hologram cannot be mistaken for a
 * visitor that way, whatever the real structure turns out to be.
 *
 * <p><b>Never guessed.</b> The reward comes only from an offer recorded when the visitor's menu was
 * opened ({@link VisitorOfferStore}); its valuable reward was picked there by
 * {@link VisitorGuard#valuableRewardIn}, the refuse-guard's own list. No offer = {@link Kind#UNKNOWN}.
 */
public final class VisitorHighlights {

    private VisitorHighlights() {
    }

    /** What the highlight knows about one visitor. */
    public enum Kind {
        /** The recorded offer includes a reward from the refuse-guard's valuable list. */
        VALUABLE,
        /** The offer is recorded and has nothing valuable in it. */
        PLAIN,
        /** The visitor is waiting but their menu has never been opened. */
        UNKNOWN
    }

    /** One matched visitor: their tab name, what is known, and the valuable reward if any. */
    public record Verdict(String visitor, Kind kind, String reward) {
    }

    /** A visitor's rarity, read off the colour of their name. Mapping unverified - see the spec. */
    public enum Rarity {
        UNCOMMON('a'), RARE('9'), LEGENDARY('6'), MYTHIC('d'), SPECIAL('c');

        private final char code;

        Rarity(char code) {
            this.code = code;
        }

        /** Legendary and above - what the "highlight by rarity" toggle marks. */
        public boolean notable() {
            return this == LEGENDARY || this == MYTHIC || this == SPECIAL;
        }
    }

    /**
     * The tab visitor that one of {@code candidates} names, or {@code null}. An exact match (after
     * colour codes, case and spacing) wins; otherwise the longest visitor name found as whole words
     * inside a candidate, so "Jack" never claims a line that says "Jacko".
     */
    public static String matchVisitor(List<String> candidates, List<String> tabVisitors) {
        if (candidates.isEmpty() || tabVisitors.isEmpty()) {
            return null;
        }
        for (String candidate : candidates) {
            String key = VisitorShoppingList.key(strip(candidate));
            for (String visitor : tabVisitors) {
                if (!key.isEmpty() && key.equals(VisitorShoppingList.key(visitor))) {
                    return visitor;
                }
            }
        }
        List<String> byLength = new ArrayList<>(tabVisitors);
        byLength.sort(Comparator.comparingInt(String::length).reversed());
        for (String candidate : candidates) {
            String padded = " " + VisitorShoppingList.key(strip(candidate)).replaceAll("[^a-z0-9' .]", " ") + " ";
            for (String visitor : byLength) {
                String key = VisitorShoppingList.key(visitor);
                if (!key.isEmpty() && padded.contains(" " + key + " ")) {
                    return visitor;
                }
            }
        }
        return null;
    }

    /**
     * What is known about {@code visitor}. {@code offers} is keyed by
     * {@link VisitorShoppingList#key}, the store's own key.
     */
    public static Verdict classify(String visitor, Map<String, VisitorShoppingList.Offer> offers) {
        VisitorShoppingList.Offer offer = offers.get(VisitorShoppingList.key(visitor));
        if (offer == null) {
            return new Verdict(visitor, Kind.UNKNOWN, null);
        }
        String reward = offer.rareReward();
        return reward == null || reward.isBlank()
                ? new Verdict(visitor, Kind.PLAIN, null)
                : new Verdict(visitor, Kind.VALUABLE, reward.trim());
    }

    /** The rarity the first colour code of a raw (§-coded) name says, or {@code null}. */
    public static Rarity rarityOf(String raw) {
        if (raw == null) {
            return null;
        }
        for (int i = 0; i + 1 < raw.length(); i++) {
            if (raw.charAt(i) != '§') {
                continue;
            }
            char code = Character.toLowerCase(raw.charAt(i + 1));
            for (Rarity rarity : Rarity.values()) {
                if (rarity.code == code) {
                    return rarity;
                }
            }
            if ("0123456789abcdef".indexOf(code) >= 0) {
                return null;   // the first colour is not a rarity colour
            }
        }
        return null;
    }

    /** "★ Jack · Overgrown Grass". */
    public static String label(Verdict verdict) {
        return switch (verdict.kind()) {
            case VALUABLE -> "★ " + verdict.visitor() + " · " + verdict.reward();
            case UNKNOWN -> "? " + verdict.visitor() + " · open to check";
            case PLAIN -> verdict.visitor();
        };
    }

    static String strip(String text) {
        return text == null ? "" : text.replaceAll("§.", "").toLowerCase(Locale.ROOT).trim();
    }
}
