/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.logic;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.economy.bazaar.logic.LocalFlipEngine;
import sbs.modid.client.economy.prices.BazaarPriceCache;
import sbs.modid.client.helper.rift.model.Certainty;
import sbs.modid.client.skills.mining.model.GemstoneTier;

/**
 * What a gemstone is worth, and whether combining it first would be worth more.
 *
 * <p><b>Instant-sell, after tax.</b> The figure is what hits the purse if the player sells right now:
 * {@code quick_status.sellPrice} - the highest standing buy order - minus the Bazaar sell tax from
 * {@link LocalFlipEngine#taxRate}. The instant-<i>buy</i> price is the wrong side of the book for
 * this question and would overstate every number on the card. The tax is not optional either: at 1.25%
 * of a fourteen-million-coin Perfect gemstone it is most of an hour's difference between two mining
 * spots.
 *
 * <p><b>The ladder is the point.</b> A rough gemstone and the flawless one it becomes are three
 * orders of magnitude apart in unit value, so "coins per hour from gemstones" is not one number until
 * somebody has said which tier is being sold. The mod cannot observe that - what the player does at
 * the Bazaar is not visible from here - so it does not guess: it prices what was actually mined, and
 * separately reports what each rung of the ladder would be worth, letting the player pick. The
 * comparison is only ever advice, and it is labelled with how much the ratio behind it is trusted.
 *
 * <p><b>Combining is assumed free of coins</b>, which is true of the Gemstone Grinder as far as anyone
 * here has confirmed - and stated rather than buried, because if a fee does exist every combine figure
 * is optimistic by it. The real cost that is modelled is the one that always applies: combining is
 * lossy in the sense that it takes {@code n} of a thing to make one, so the comparison is always per
 * unit of the <i>input</i>.
 */
public final class GemstoneProfit {

    /**
     * Hourly output above this share of a product's own hourly traded volume gets flagged. A tenth is
     * where a single seller stops being a price-taker: dumping that into a book which only absorbs ten
     * times as much in an hour moves it, and the quoted price stops describing what you would get.
     */
    private static final double VOLUME_WARN_SHARE = 0.10;

    private GemstoneProfit() {
    }

    private static double taxRate() {
        return LocalFlipEngine.taxRate(
                ConfigManager.getInstance().get().gemstoneProfit.bazaarFlipperLevel);
    }

    /**
     * What one gem sells for right now, net of tax, or {@code -1} when the Bazaar has not said.
     *
     * <p>{@code -1} rather than {@code 0}: a product with no price is not a worthless product, and a
     * caller that adds a zero into a total silently reports a smaller figure as though it were
     * complete. Every caller here checks.
     */
    public static double netUnitValue(GemstoneCatalog.Gem gem) {
        if (gem == null) {
            return -1;
        }
        BazaarPriceCache.BzPrice price = BazaarPriceCache.getInstance().get(gem.bazaarId());
        if (price == null || price.sell() <= 0) {
            return -1;
        }
        return price.sell() * (1.0 - taxRate());
    }

    /**
     * One rung of the ladder for a gem the player is holding {@code count} of: what selling at that
     * grade is worth in total, and how it compares with selling as mined.
     *
     * @param tier        the grade being sold at
     * @param unitsAtTier how many of that grade the {@code count} converts into (fractional)
     * @param netValue    total coins after tax, or {@code -1} when that grade has no price
     * @param certainty   how much the conversion behind {@code unitsAtTier} is trusted
     */
    public record Rung(GemstoneTier tier, double unitsAtTier, double netValue, Certainty certainty) {

        public boolean priced() {
            return netValue >= 0;
        }
    }

    /**
     * The whole ladder from the mined grade upward, cheapest rung first.
     *
     * <p>{@code certainty} degrades as it climbs: the first rung is what was actually mined and is as
     * certain as the price feed, and every rung above it multiplies in one more unverified ratio. The
     * UI shows that, so a recommendation two steps up is visibly a weaker claim than one step up
     * rather than looking equally authoritative.
     */
    public static java.util.List<Rung> ladder(GemstoneCatalog.Gem mined, double count) {
        java.util.List<Rung> rungs = new java.util.ArrayList<>(GemstoneTier.values().length);
        if (mined == null || count <= 0) {
            return rungs;
        }
        GemstoneCatalog.Gem gem = mined;
        double units = count;
        // The mined grade itself is trusted as far as the price is: no ratio has been applied yet.
        Certainty certainty = Certainty.CONFIRMED;
        while (gem != null) {
            double unit = netUnitValue(gem);
            rungs.add(new Rung(gem.tier(), units, unit < 0 ? -1 : unit * units, certainty));
            int per = gem.tier().perNext();
            GemstoneCatalog.Gem next = gem.next();
            if (next == null || per <= 0) {
                break;
            }
            units /= per;
            certainty = weaker(certainty, gem.tier().ratioCertainty());
            gem = next;
        }
        return rungs;
    }

    /** The weaker of two certainties, since a chain is only as good as its worst link. */
    private static Certainty weaker(Certainty a, Certainty b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    /**
     * The best rung of a ladder, or {@code null} when nothing on it is priced. Ties go to the lower
     * grade - combining costs the player time and menu clicks, so an equal figure is not a reason to
     * recommend more work.
     */
    public static Rung best(java.util.List<Rung> ladder) {
        Rung best = null;
        for (Rung rung : ladder) {
            if (rung.priced() && (best == null || rung.netValue() > best.netValue())) {
                best = rung;
            }
        }
        return best;
    }

    /**
     * Whether an hourly output of {@code perHour} units of {@code gem} is large against what that
     * product actually trades, and the share it represents.
     *
     * <p>Measured against the weekly moving volume rather than the resting order book: the book is a
     * snapshot that one sell can clear and refills, while the weekly figure is throughput and is the
     * thing an hourly rate is comparable to. Returns {@code null} when the volume is unknown - which
     * is a reason to say nothing, not a reason to warn.
     */
    public record VolumeCheck(double share, double marketPerHour, boolean heavy) {
    }

    public static VolumeCheck volumeCheck(GemstoneCatalog.Gem gem, double perHour) {
        if (gem == null || perHour <= 0) {
            return null;
        }
        BazaarPriceCache.BzVolume volume = BazaarPriceCache.getInstance().volume(gem.bazaarId());
        if (volume == null || volume.sellWeek() <= 0) {
            return null;
        }
        double marketPerHour = volume.sellPerHour();
        double share = marketPerHour <= 0 ? Double.MAX_VALUE : perHour / marketPerHour;
        return new VolumeCheck(share, marketPerHour, share >= VOLUME_WARN_SHARE);
    }

    /** The sell tax as a percentage, for the card's assumptions line. */
    public static double taxPercent() {
        return taxRate() * 100.0;
    }

    /** How stale the prices behind every figure are. Shown, never hidden. */
    public static long priceAgeMs() {
        return BazaarPriceCache.getInstance().priceAgeMs();
    }
}
