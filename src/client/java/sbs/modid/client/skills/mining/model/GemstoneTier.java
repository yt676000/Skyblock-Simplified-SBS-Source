/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.model;

import sbs.modid.client.helper.rift.model.Certainty;

/**
 * The five gemstone grades, and how many of one make the next.
 *
 * <p><b>The ratios are the uncertain part of this feature</b>, so they carry a {@link Certainty} and
 * the UI shows it. They are not in any API: the Hypixel items resource returns a null {@code recipe}
 * for every gemstone tier and no other item's recipe references one, so combining is a menu
 * interaction with nothing published behind it. They can only be read off the Gemstone Grinder, which
 * is what {@code /sbs probe} on that menu is for - see {@code GemstoneCombine} for the learned copy
 * that supersedes these.
 *
 * <p><b>Why a wrong ratio matters so much.</b> Every "sell now or combine" answer is a ratio times a
 * price, and the tiers are three to four orders of magnitude apart in unit value - so a ratio that is
 * wrong by a factor of sixteen does not produce a slightly wrong recommendation, it produces a
 * confidently inverted one. Hence: shipped as hypotheses, overwritten by observation, and labelled
 * until then.
 *
 * <p><b>What the live market says about them.</b> Measured against the Bazaar snapshot across all
 * twelve gemstone types, the implied ratio (instant-buy of the next tier over instant-buy of this
 * one) has a median of 79.7 for Rough→Flawed and 80.3 for Fine→Flawless - both sitting on 80 to
 * within a rounding error, which is what arbitrage between two freely convertible goods looks like
 * and is decent corroboration. The other two steps do <b>not</b> sit on their assumed ratio:
 * Flawed→Fine implies 54.3 against an assumed 80, and Flawless→Perfect implies 6.3 against an assumed
 * 5. That is a real finding rather than noise - it says combining is value-destroying at one step and
 * value-creating at another - but it is only a finding if the ratio is right, which is exactly why
 * these two are the ones to confirm first.
 */
public enum GemstoneTier {

    /** What the ore actually drops. */
    ROUGH("Rough", "ROUGH", 80, Certainty.ESTIMATED),
    FLAWED("Flawed", "FLAWED", 80, Certainty.WIKI),
    FINE("Fine", "FINE", 80, Certainty.ESTIMATED),
    FLAWLESS("Flawless", "FLAWLESS", 5, Certainty.WIKI),
    /** The top grade; nothing combines out of it. */
    PERFECT("Perfect", "PERFECT", 0, Certainty.CONFIRMED);

    private final String displayName;
    private final String idPrefix;
    private final int perNext;
    private final Certainty ratioCertainty;

    GemstoneTier(String displayName, String idPrefix, int perNext, Certainty ratioCertainty) {
        this.displayName = displayName;
        this.idPrefix = idPrefix;
        this.perNext = perNext;
        this.ratioCertainty = ratioCertainty;
    }

    public String displayName() {
        return displayName;
    }

    /** The Bazaar id prefix: {@code ROUGH} + {@code _JADE_GEM}. */
    public String idPrefix() {
        return idPrefix;
    }

    /** How many of this tier make one of the next, or {@code 0} at the top of the ladder. */
    public int perNext() {
        return perNext;
    }

    /**
     * How much the {@link #perNext()} figure is worth trusting. {@link Certainty#CONFIRMED} on
     * {@link #PERFECT} is not a measured ratio - it is the trivially true statement that there is no
     * tier above it.
     */
    public Certainty ratioCertainty() {
        return ratioCertainty;
    }

    /** The next grade up, or {@code null} at the top. */
    public GemstoneTier next() {
        return this == PERFECT ? null : values()[ordinal() + 1];
    }

    /** The grade below, or {@code null} at the bottom. */
    public GemstoneTier previous() {
        return this == ROUGH ? null : values()[ordinal() - 1];
    }
}
