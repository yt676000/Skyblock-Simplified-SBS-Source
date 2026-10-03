/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.bazaar.prerender;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.core.component.DataComponents;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.economy.prices.BazaarPriceCache;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The measurements phase 1 exists to take.
 *
 * <p>Four questions, none of which can be answered by reading the code, and all of which have to be
 * answered before a cached Bazaar screen is put in front of a player:
 *
 * <ol>
 *   <li><b>Would the cache have been right?</b> How often an arriving screen matched the stored one
 *       exactly, and — separately — how often it matched <i>structurally</i>, meaning the same slots
 *       were filled and only the contents moved. The gap between those two numbers is the feature's
 *       premise stated as data: a static layout with live prices inside it.</li>
 *   <li><b>Which slots moved</b> when it did not match, so a mismatch can be attributed to prices
 *       rather than to the layout genuinely changing.</li>
 *   <li><b>How much latency is actually being hidden</b>, measured from the player's click to the
 *       arriving container. If this turns out to be small, the whole feature is not worth its risk
 *       and phase 1 has done its job by saying so.</li>
 *   <li><b>How far our own price data sits from the arriving lore</b>, which is the number that
 *       decides whether live substitution is trustworthy.</li>
 * </ol>
 *
 * <p><b>On question 4, honestly.</b> Divergence can only be computed once the price lines in Bazaar
 * lore can be located, and no such parser exists in this mod — the only lore parsing that exists
 * reads the orders menu. So this class does not pretend to measure it: it records the candidate
 * lines it can see and the API figure alongside, and leaves the comparison to a human reading the
 * log. Emitting a confident divergence percentage from a guessed line would be the exact failure the
 * price modes are designed around.
 *
 * <p>Everything here logs under {@code [SBS][BzCache]} and holds only counters — no state that
 * outlives the session, and nothing written to disk.
 */
public final class BazaarPrerenderDiagnostics {

    /** Differing slots printed per mismatch; the rest are counted. A full 54 would bury the log. */
    private static final int MAX_LISTED_SLOTS = 12;

    private static final BazaarPrerenderDiagnostics INSTANCE = new BazaarPrerenderDiagnostics();

    private int arrivals;
    private int exactMatches;
    private int structuralMatches;
    private int firstSightings;
    private int refusedPaging;
    private int refusedPlayerSpecific;

    private int latencySamples;
    private long latencyTotalMs;
    private long latencyWorstMs;

    private BazaarPrerenderDiagnostics() {
    }

    public static BazaarPrerenderDiagnostics getInstance() {
        return INSTANCE;
    }

    /**
     * A Bazaar screen arrived: compare what was stored against what came.
     *
     * @param cached   what the cache held before this visit, or {@code null} on a first sighting
     * @param arriving what the server actually sent
     */
    public void recordArrival(String key, CapturedScreen cached, CapturedScreen arriving) {
        arrivals++;
        if (cached == null) {
            firstSightings++;
            SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][BzCache] first sighting '{}' ({} slots) - nothing to compare", key,
                    arriving.slotCount);
            return;
        }
        List<Integer> differing = cached.diff(arriving);
        List<Integer> structural = cached.structuralDiff(arriving);
        if (differing.isEmpty()) {
            exactMatches++;
        }
        if (structural.isEmpty()) {
            structuralMatches++;
        }
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][BzCache] arrival '{}' age={}s exact={} differing={}/{} structural={} {}",
                key, cached.ageMs() / 1000L, differing.isEmpty(), differing.size(),
                arriving.slotCount, structural.isEmpty() ? "same" : structural.size() + " slots",
                differing.isEmpty() ? "" : "slots=" + summarize(differing));
    }

    /** Click-to-container latency: the amount of waiting a preview would actually be hiding. */
    public void recordLatency(String fromKey, String toKey, long millis) {
        latencySamples++;
        latencyTotalMs += millis;
        latencyWorstMs = Math.max(latencyWorstMs, millis);
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][BzCache] latency {}ms '{}' -> '{}' (mean {}ms over {}, worst {}ms)",
                millis, fromKey, toKey, latencyTotalMs / Math.max(1, latencySamples), latencySamples,
                latencyWorstMs);
    }

    /** A screen that pages without saying which page it is on. Counted because it decides the key design. */
    public void countRefusedPaging(String rawTitle) {
        refusedPaging++;
        if (refusedPaging <= 5 || refusedPaging % 25 == 0) {
            SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][BzCache] refused (paged, no counter in title) '{}' - {} so far",
                    rawTitle, refusedPaging);
        }
    }

    /** A product page, orders menu or confirm flow: player state, refused from the global cache. */
    public void countRefusedPlayerSpecific() {
        refusedPlayerSpecific++;
    }

    /**
     * Logs the candidate price lines on one item beside what our own cache says it is worth.
     *
     * <p>Deliberately not a divergence figure. It prints the lines that look like they carry a
     * number and the API's buy/sell for the same product, so the two can be read together and the
     * lore format established from evidence. Once the format is known this becomes a real
     * comparison; until then, a percentage here would be a guess wearing a decimal point.
     */
    public void samplePriceDivergence(String skyblockId, ItemStack stack) {
        if (skyblockId == null || stack == null || stack.isEmpty()) {
            return;
        }
        BazaarPriceCache.BzPrice price = BazaarPriceCache.getInstance().get(skyblockId);
        List<String> candidates = new ArrayList<>();
        for (String line : loreOf(stack)) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.matches(".*\\d.*") && (lower.contains("coin") || lower.contains("price")
                    || lower.contains("buy") || lower.contains("sell"))) {
                candidates.add(line);
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][BzCache] price lore '{}' api={} candidates:",
                skyblockId, price == null ? "unknown" : price.buy() + "/" + price.sell());
        for (String line : candidates) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][BzCache]     ? {}", line);
        }
    }

    /** The one-line session summary: the numbers phase 2 is decided on. */
    public void logSummary() {
        BazaarScreenStore store = BazaarScreenStore.getInstance();
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][BzCache] summary: {} arrivals, {} exact, {} structural, {} first sightings, "
                        + "{} refused (paged), {} refused (player state); latency mean {}ms worst {}ms "
                        + "over {}; store {} screens ~{} KB uncompressed",
                arrivals, exactMatches, structuralMatches, firstSightings, refusedPaging,
                refusedPlayerSpecific, latencyTotalMs / Math.max(1, latencySamples), latencyWorstMs,
                latencySamples, store.size(), store.approximateBytes() / 1024);
    }

    /**
     * The differing slot indices, truncated.
     *
     * <p>Truncated rather than summarised into a count, because <i>which</i> slots moved is the
     * whole diagnostic: prices moving shows up as the item slots differing while the frame and the
     * navigation stay put, and that shape is only visible if the indices are printed.
     */
    private static String summarize(List<Integer> slots) {
        StringBuilder out = new StringBuilder();
        int listed = Math.min(MAX_LISTED_SLOTS, slots.size());
        for (int i = 0; i < listed; i++) {
            out.append(i == 0 ? "" : ",").append(slots.get(i));
        }
        if (slots.size() > listed) {
            out.append(",+").append(slots.size() - listed).append(" more");
        }
        return out.toString();
    }

    /** Colour-stripped lore lines of a stack; empty when it carries none. */
    public static List<String> loreOf(ItemStack stack) {
        List<String> out = new ArrayList<>();
        if (stack == null || stack.isEmpty()) {
            return out;
        }
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return out;
        }
        for (var line : lore.lines()) {
            out.add(line.getString());
        }
        return out;
    }
}
