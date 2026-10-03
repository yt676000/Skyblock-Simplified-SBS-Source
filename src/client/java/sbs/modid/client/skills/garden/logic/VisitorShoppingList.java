/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.garden.logic;

import sbs.modid.client.skills.garden.logic.VisitorMenu.Required;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The visitor shopping list, as pure arithmetic over plain data: who is waiting (from the tab
 * list), who has been served (from chat), and what everything known adds up to. No game classes,
 * so all of it is unit-tested.
 *
 * <p><b>The tab widget - {@code CONFIRMED}</b> from 310 tab dumps in the maintainer's logs
 * (2026-09): the rows read {@code Visitors: (5)}, then one visitor name per row, then
 * {@code Next Visitor: Queue Full!} (or its countdown). So the waiting visitors are known before
 * any of their menus is opened; what they want is not.
 *
 * <p><b>The accept line - {@code ESTIMATED}.</b> None of those logs holds a served or refused
 * visitor, so the wording {@code OFFER ACCEPTED with Jack (UNCOMMON)} is from memory of the game.
 * It is not the removal path that matters: a served or refused visitor leaves the tab widget, and
 * that is what {@link #prune} acts on. The chat line only makes the removal immediate.
 */
public final class VisitorShoppingList {

    /** One visitor's known offer. {@code copper} is -1 when the menu did not show it. */
    public record Offer(String visitor, List<Required> items, int copper, String rareReward) {
    }

    /** One line of the list: an item summed across every known visitor. */
    public record Line(String name, int needed, int have, Long unitPrice) {

        public int missing() {
            return Math.max(0, needed - have);
        }

        /** Insta-buy cost of what is still missing, or {@code null} when unpriced. */
        public Long cost() {
            return unitPrice == null ? null : unitPrice * missing();
        }
    }

    /** One visitor's side of it: what their offer costs to fill from the Bazaar, and what it pays. */
    public record VisitorCost(Offer offer, long cost, boolean fullyPriced) {

        /** Coins per copper, or {@code null} when there is no copper to divide by. */
        public Double coinsPerCopper() {
            return offer.copper() > 0 ? (double) cost / offer.copper() : null;
        }
    }

    private static final Pattern WIDGET_HEADER = Pattern.compile("^Visitors:\\s*\\((\\d+)\\)$");
    private static final Pattern ACCEPTED =
            Pattern.compile("^OFFER ACCEPTED with (.+?)(?:\\s*\\(([A-Z ]+)\\))?$");

    private VisitorShoppingList() {
    }

    // ------------------------------------------------------------------ who is waiting

    /**
     * The visitors the tab widget lists, in order; {@code null} when the widget is not in the tab
     * (not on the Garden, or the widget turned off) - which is "unknown", not "nobody".
     */
    public static List<String> waitingVisitors(List<String> tabLines) {
        for (int i = 0; i < tabLines.size(); i++) {
            Matcher header = WIDGET_HEADER.matcher(tabLines.get(i).trim());
            if (!header.matches()) {
                continue;
            }
            int count = Integer.parseInt(header.group(1));
            List<String> names = new ArrayList<>(count);
            for (int j = i + 1; j < tabLines.size() && names.size() < count; j++) {
                String row = tabLines.get(j).trim();
                if (row.isEmpty() || row.contains(":")) {
                    break;   // "Next Visitor: ..." or the next widget's heading
                }
                names.add(row);
            }
            return names;
        }
        return null;
    }

    /** The count in the widget header "Visitors: (N)", or {@code -1} when the widget is absent. */
    public static int waitingCount(List<String> tabLines) {
        for (String line : tabLines) {
            Matcher header = WIDGET_HEADER.matcher(line.trim());
            if (header.matches()) {
                return Integer.parseInt(header.group(1));
            }
        }
        return -1;
    }

    /** The visitor a chat line says was just served, or {@code null}. */
    public static String servedVisitor(String chatLine) {
        if (chatLine == null) {
            return null;
        }
        Matcher m = ACCEPTED.matcher(chatLine.trim());
        return m.matches() ? m.group(1).trim() : null;
    }

    /** Drops every offer whose visitor is no longer waiting. Returns whether anything was dropped. */
    public static boolean prune(Map<String, Offer> offers, List<String> waiting) {
        if (waiting == null) {
            return false;   // widget not visible: we do not know, so keep everything
        }
        List<String> keys = new ArrayList<>();
        for (String name : waiting) {
            keys.add(key(name));
        }
        return offers.keySet().removeIf(k -> !keys.contains(k));
    }

    /** The map key for a visitor name: case and spacing never make two entries. */
    public static String key(String visitor) {
        return visitor == null ? "" : visitor.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ the sums

    /**
     * Every wanted item summed across the offers, in first-seen order.
     *
     * @param have      how many of an item (by display name) the player already holds
     * @param unitPrice insta-buy price per item (by display name), {@code null} when unpriced
     */
    public static List<Line> lines(Iterable<Offer> offers, Function<String, Integer> have,
                                   Function<String, Long> unitPrice) {
        Map<String, int[]> needed = new LinkedHashMap<>();
        Map<String, String> shownName = new LinkedHashMap<>();
        for (Offer offer : offers) {
            for (Required item : offer.items()) {
                String k = key(item.name());
                shownName.putIfAbsent(k, item.name());
                needed.computeIfAbsent(k, x -> new int[1])[0] += item.amount();
            }
        }
        List<Line> out = new ArrayList<>(needed.size());
        for (var entry : needed.entrySet()) {
            String name = shownName.get(entry.getKey());
            Integer held = have.apply(name);
            out.add(new Line(name, entry.getValue()[0], held == null ? 0 : held, unitPrice.apply(name)));
        }
        return out;
    }

    /**
     * What each offer costs to fill entirely from the Bazaar (the items you hold are shared across
     * visitors, so they are not credited per visitor - that is the list's job), cheapest copper
     * first; offers without copper go last.
     */
    public static List<VisitorCost> visitorCosts(Iterable<Offer> offers, Function<String, Long> unitPrice) {
        List<VisitorCost> out = new ArrayList<>();
        for (Offer offer : offers) {
            long cost = 0;
            boolean priced = true;
            for (Required item : offer.items()) {
                Long unit = unitPrice.apply(item.name());
                if (unit == null) {
                    priced = false;
                } else {
                    cost += unit * item.amount();
                }
            }
            out.add(new VisitorCost(offer, cost, priced));
        }
        out.sort((a, b) -> {
            Double ca = a.coinsPerCopper();
            Double cb = b.coinsPerCopper();
            if (ca == null || cb == null) {
                return ca == null ? (cb == null ? 0 : 1) : -1;
            }
            return Double.compare(ca, cb);
        });
        return out;
    }
}
