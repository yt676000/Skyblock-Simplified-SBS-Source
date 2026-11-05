/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.dice.logic;

import sbs.modid.client.economy.dice.model.DiceKind;

import java.util.EnumMap;
import java.util.Map;

/**
 * The counts behind the card, per dice kind, and the profit sum over them. Pure and Gson-friendly:
 * the all-time copy is the profile file.
 *
 * <p>Items are counted, not valued: the dice lost and the dyes found are priced at display time,
 * against the current lowest BIN, so the net follows the market instead of freezing the price of
 * the day it happened.
 */
public final class DiceLedger {

    /** One kind's tally. {@code faces[v]} is how often {@code v} was rolled; index 0 is unused. */
    public static final class Counts {
        public long rolls;
        public long spent;
        public long won;
        public long diceLost;
        public long dyes;
        public long[] faces = new long[8];

        public long net(Long dicePrice, Long dyePrice, boolean countDiceLoss) {
            long net = won - spent + dyes * orZero(dyePrice);
            if (countDiceLoss) {
                net -= diceLost * orZero(dicePrice);
            }
            return net;
        }

        private static long orZero(Long price) {
            return price == null ? 0L : price;
        }
    }

    private Map<DiceKind, Counts> kinds = new EnumMap<>(DiceKind.class);

    /**
     * One roll. A 6 pays out and a 7 gives the dye; both consume the dice, which is counted here
     * and nowhere else, so a later payout or dye line can never count it twice.
     */
    public void roll(DiceKind kind, int value) {
        if (value < 1 || value > 7) {
            return;
        }
        Counts c = counts(kind);
        c.rolls++;
        c.spent += kind.rollCost();
        c.faces[value]++;
        if (value == 6) {
            c.won += kind.sixPayout();
            c.diceLost++;
        } else if (value == 7) {
            c.dyes++;
            c.diceLost++;
        }
    }

    /** This kind's tally, or {@code null} when it was never rolled. */
    public Counts get(DiceKind kind) {
        return kinds == null ? null : kinds.get(kind);
    }

    public void clear() {
        kinds = new EnumMap<>(DiceKind.class);
    }

    private Counts counts(DiceKind kind) {
        if (kinds == null) {
            kinds = new EnumMap<>(DiceKind.class);
        }
        Counts c = kinds.computeIfAbsent(kind, k -> new Counts());
        if (c.faces == null || c.faces.length != 8) {
            c.faces = new long[8];
        }
        return c;
    }
}
